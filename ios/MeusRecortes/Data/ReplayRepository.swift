import Foundation
import Photos
import UIKit

struct ClipInfo: Identifiable, Hashable {
    let id: UUID
    let url: URL
    let displayName: String
    let duration: Double
    let sizeBytes: Int64
    let dateAdded: Date
}

class ReplayRepository: ObservableObject {
    
    static let shared = ReplayRepository()
    
    private let defaults = UserDefaults.standard
    private let fileManager = FileManager.default
    
    // Chaves de preferência
    private let keyAntesSegundos = "antes_segundos"
    private let keyDepoisSegundos = "depois_segundos"
    private let keyDeviceId = "device_id"
    private let keyIsActivated = "is_activated"
    private let keyLicenseKey = "license_key"
    
    @Published var savedClips: [ClipInfo] = []
    
    private init() {
        loadSavedClips()
    }
    
    // Configurações de tempo
    func getAntesSegundos() -> Int {
        let value = defaults.integer(forKey: keyAntesSegundos)
        return value == 0 ? 5 : value
    }
    
    func getDepoisSegundos() -> Int {
        let value = defaults.integer(forKey: keyDepoisSegundos)
        return value == 0 ? 5 : value
    }
    
    func saveConfig(antes: Int, depois: Int) {
        defaults.set(antes, forKey: keyAntesSegundos)
        defaults.set(depois, forKey: keyDepoisSegundos)
    }
    
    /**
     * Retorna o Device ID exclusivo deste aparelho.
     */
    func getDeviceId() -> String {
        if let deviceId = defaults.string(forKey: keyDeviceId) {
            return deviceId
        }
        let newId = UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(8).uppercased()
        defaults.set(newId, forKey: keyDeviceId)
        return newId
    }
    
    /**
     * Verifica se o aplicativo está ativado localmente.
     */
    func isActivatedLocal() -> Bool {
        if SupabaseConfig.url == "SUA_SUPABASE_URL_AQUI" {
            return true
        }
        return defaults.bool(forKey: keyIsActivated)
    }
    
    func getLocalLicenseKey() -> String {
        return defaults.string(forKey: keyLicenseKey) ?? ""
    }
    
    func saveActivationLocal(licenseKey: String) {
        defaults.set(true, forKey: keyIsActivated)
        defaults.set(licenseKey, forKey: keyLicenseKey)
    }
    
    func clearActivationLocal() {
        defaults.set(false, forKey: keyIsActivated)
        defaults.set("", forKey: keyLicenseKey)
    }
    
    // --- INTEGRAÇÃO COM SUPABASE ---
    
    struct LicenseResponse: Decodable {
        let license_key: String
        let device_id: String?
        let is_active: Bool
    }
    
    /**
     * Valida uma licença no Supabase via REST API.
     */
    func validateLicenseOnSupabase(licenseKey: String, completion: @escaping (Result<Bool, Error>) -> Void) {
        let trimmedKey = licenseKey.trimmingCharacters(in: .whitespacesAndNewlines)
        
        guard let encodedKey = trimmedKey.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed),
              let url = URL(string: "\(SupabaseConfig.url)/rest/v1/licenses?license_key=eq.\(encodedKey)&select=*") else {
            completion(.failure(NSError(domain: "ReplayRepository", code: 0, userInfo: [NSLocalizedDescriptionKey: "Chave ou URL inválida."])))
            return
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue(SupabaseConfig.anonKey, forHTTPHeaderField: "apikey")
        request.setValue("Bearer \(SupabaseConfig.anonKey)", forHTTPHeaderField: "Authorization")
        request.timeoutInterval = 10.0
        
        URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            if let error = error {
                completion(.failure(error))
                return
            }
            
            guard let httpResponse = response as? HTTPURLResponse else {
                completion(.failure(NSError(domain: "ReplayRepository", code: 0, userInfo: [NSLocalizedDescriptionKey: "Erro de rede desconhecido."])))
                return
            }
            
            if httpResponse.statusCode != 200 {
                completion(.failure(NSError(domain: "ReplayRepository", code: httpResponse.statusCode, userInfo: [NSLocalizedDescriptionKey: "Erro de conexão com o servidor (\(httpResponse.statusCode))."])))
                return
            }
            
            guard let data = data else {
                completion(.failure(NSError(domain: "ReplayRepository", code: 0, userInfo: [NSLocalizedDescriptionKey: "Resposta do servidor vazia."])))
                return
            }
            
            do {
                let licenses = try JSONDecoder().decode([LicenseResponse].bind(to: [LicenseResponse].self), from: data)
                
                guard let license = licenses.first else {
                    completion(.failure(NSError(domain: "ReplayRepository", code: 0, userInfo: [NSLocalizedDescriptionKey: "Chave de licença inválida ou inexistente."])))
                    return
                }
                
                if !license.is_active {
                    completion(.failure(NSError(domain: "ReplayRepository", code: 0, userInfo: [NSLocalizedDescriptionKey: "Esta licença foi desativada pelo administrador."])))
                    return
                }
                
                let myDeviceId = self?.getDeviceId() ?? ""
                
                if let registeredId = license.device_id, registeredId != "null", registeredId != myDeviceId {
                    completion(.failure(NSError(domain: "ReplayRepository", code: 0, userInfo: [NSLocalizedDescriptionKey: "Esta licença já está em uso em outro aparelho."])))
                    return
                }
                
                // Se a licença não estiver vinculada a nenhum device, fazemos o PATCH para vincular
                if license.device_id == nil {
                    self?.associateDeviceOnSupabase(licenseKey: trimmedKey, deviceId: myDeviceId) { patchResult in
                        switch patchResult {
                        case .success:
                            self?.saveActivationLocal(licenseKey: trimmedKey)
                            completion(.success(true))
                        case .failure(let patchError):
                            completion(.failure(patchError))
                        }
                    }
                } else {
                    // Já estava vinculada a este dispositivo
                    self?.saveActivationLocal(licenseKey: trimmedKey)
                    completion(.success(true))
                }
                
            } catch {
                completion(.failure(NSError(domain: "ReplayRepository", code: 0, userInfo: [NSLocalizedDescriptionKey: "Erro de formato nos dados da licença."])))
            }
        }.resume()
    }
    
    /**
     * Vincula o Device ID do aparelho à licença no Supabase (PATCH).
     */
    private func associateDeviceOnSupabase(licenseKey: String, deviceId: String, completion: @escaping (Result<Void, Error>) -> Void) {
        guard let encodedKey = licenseKey.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed),
              let url = URL(string: "\(SupabaseConfig.url)/rest/v1/licenses?license_key=eq.\(encodedKey)") else {
            completion(.failure(NSError(domain: "ReplayRepository", code: 0, userInfo: [NSLocalizedDescriptionKey: "URL inválida para vinculação."])))
            return
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "PATCH"
        request.setValue(SupabaseConfig.anonKey, forHTTPHeaderField: "apikey")
        request.setValue("Bearer \(SupabaseConfig.anonKey)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.timeoutInterval = 10.0
        
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let isoDate = formatter.string(from: Date())
        
        let body: [String: Any] = [
            "device_id": deviceId,
            "activated_at": isoDate
        ]
        
        do {
            request.httpBody = try JSONSerialization.data(withJSONObject: body, options: [])
        } catch {
            completion(.failure(error))
            return
        }
        
        URLSession.shared.dataTask(with: request) { data, response, error in
            if let error = error {
                completion(.failure(error))
                return
            }
            
            guard let httpResponse = response as? HTTPURLResponse else {
                completion(.failure(NSError(domain: "ReplayRepository", code: 0, userInfo: [NSLocalizedDescriptionKey: "Resposta de rede inválida no registro."])))
                return
            }
            
            if httpResponse.statusCode == 200 || httpResponse.statusCode == 204 {
                completion(.success(()))
            } else {
                var errorText = ""
                if let data = data {
                    errorText = String(data: data, encoding: .utf8) ?? ""
                }
                completion(.failure(NSError(domain: "ReplayRepository", code: httpResponse.statusCode, userInfo: [NSLocalizedDescriptionKey: "Erro ao registrar dispositivo no servidor: \(errorText)"])))
            }
        }.resume()
    }
    
    /**
     * Valida de forma rápida na inicialização se a licença ainda é válida.
     */
    func checkLicenseStatusOnStartup(completion: @escaping (Bool) -> Void) {
        guard isActivatedLocal() else {
            completion(false)
            return
        }
        
        let key = getLocalLicenseKey()
        let myDeviceId = getDeviceId()
        
        guard let encodedKey = key.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed),
              let url = URL(string: "\(SupabaseConfig.url)/rest/v1/licenses?license_key=eq.\(encodedKey)&select=*") else {
            completion(false)
            return
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue(SupabaseConfig.anonKey, forHTTPHeaderField: "apikey")
        request.setValue("Bearer \(SupabaseConfig.anonKey)", forHTTPHeaderField: "Authorization")
        request.timeoutInterval = 4.0
        
        URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            guard error == nil,
                  let httpResponse = response as? HTTPURLResponse,
                  httpResponse.statusCode == 200,
                  let data = data else {
                // Em caso de falha de rede/timeout, tolera permitindo uso offline
                completion(true)
                return
            }
            
            do {
                let licenses = try JSONDecoder().decode([LicenseResponse].self, from: data)
                if let license = licenses.first, license.is_active, license.device_id == myDeviceId {
                    completion(true)
                } else {
                    // Revogada!
                    self?.clearActivationLocal()
                    completion(false)
                }
            } catch {
                completion(true) // Erro de decodificação, tolera offline
            }
        }.resume()
    }
    
    // --- GERENCIAMENTO DE ARQUIVOS LOCAIS ---
    
    var clipsDirectory: URL {
        let paths = fileManager.urls(for: .documentDirectory, in: .userDomainMask)
        let directory = paths[0].appendingPathComponent("clips", isDirectory: true)
        if !fileManager.fileExists(atPath: directory.path) {
            try? fileManager.createDirectory(at: directory, withIntermediateDirectories: true, attributes: nil)
        }
        return directory
    }
    
    /**
     * Varre a pasta de Documentos para carregar a lista de lances salvos.
     */
    func loadSavedClips() {
        let directory = clipsDirectory
        do {
            let files = try fileManager.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.creationDateKey, .fileSizeKey], options: .skipsHiddenFiles)
            
            let clips = files.filter { $0.pathExtension.lowercased() == "mp4" }.map { url -> ClipInfo in
                let attributes = try? fileManager.attributesOfItem(atPath: url.path)
                let date = attributes?[.creationDate] as? Date ?? Date()
                let size = attributes?[.size] as? Int64 ?? 0
                
                // Pegar duração aproximada
                let asset = AVURLAsset(url: url)
                let duration = CMTimeGetSeconds(asset.duration)
                
                return ClipInfo(
                    id: UUID(),
                    url: url,
                    displayName: url.lastPathComponent,
                    duration: duration.isNaN ? 0.0 : duration,
                    sizeBytes: size,
                    dateAdded: date
                )
            }.sorted(by: { $0.dateAdded > $1.dateAdded })
            
            DispatchQueue.main.async {
                self.savedClips = clips
            }
        } catch {
            print("Erro ao listar diretório de clips: \(error)")
        }
    }
    
    func deleteClip(_ clip: ClipInfo) {
        try? fileManager.removeItem(at: clip.url)
        loadSavedClips()
    }
    
    /**
     * Salva o clipe final na galeria de fotos do iPhone (Photo Library).
     */
    func saveToPhotoLibrary(videoURL: URL, completion: @escaping (Bool) -> Void) {
        PHPhotoLibrary.requestAuthorization { status in
            if status == .authorized {
                PHPhotoLibrary.shared().performChanges({
                    PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: videoURL)
                }) { success, error in
                    completion(success)
                }
            } else {
                completion(false)
            }
        }
    }
}

// Extensão utilitária para contornar problemas de decodificação de JSON no Swift
extension JSONDecoder {
    func decode<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        return try self.decode(type, from: data)
    }
}
