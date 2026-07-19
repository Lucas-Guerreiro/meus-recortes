import Foundation
import Combine

class MainViewModel: ObservableObject {
    
    private let repository = ReplayRepository.shared
    private let bufferManager = VideoBufferManager.shared
    private var cancellables = Set<AnyCancellable>()
    
    // Estados do Replay / Configurações
    @Published var antesSegundos = 5
    @Published var depoisSegundos = 5
    @Published var savedClips: [ClipInfo] = []
    @Published var recordingState = VideoBufferManager.RecordingState.idle
    @Published var toastMessage: String? = nil
    
    // Estados da Ativação (Supabase)
    @Published var isActivated = false
    @Published var deviceId = ""
    @Published var isActivating = false
    @Published var activationError: String? = nil
    @Published var activeVideoURL: URL? = nil
    
    init() {
        self.antesSegundos = repository.getAntesSegundos()
        self.depoisSegundos = repository.getDepoisSegundos()
        self.deviceId = repository.getDeviceId()
        self.isActivated = repository.isActivatedLocal()
        
        // 1. Observar a lista de replays do repositório
        repository.$savedClips
            .receive(on: RunLoop.main)
            .assign(to: \.savedClips, on: self)
            .store(in: &cancellables)
        
        // 2. Observar o estado do buffer de vídeo
        bufferManager.$state
            .receive(on: RunLoop.main)
            .assign(to: \.recordingState, on: self)
            .store(in: &cancellables)
        
        // 3. Observar mensagens de eventos (toasts)
        bufferManager.$eventMessage
            .receive(on: RunLoop.main)
            .sink { [weak self] msg in
                self?.toastMessage = msg
            }
            .store(in: &cancellables)
        
        // 4. Verificar licença remotamente em background na inicialização
        checkLicenseRemote()
    }
    
    func setAntesSegundos(_ value: Int) {
        self.antesSegundos = value
        bufferManager.updateConfig(antes: value, depois: depoisSegundos)
    }
    
    func setDepoisSegundos(_ value: Int) {
        self.depoisSegundos = value
        bufferManager.updateConfig(antes: antesSegundos, depois: value)
    }
    
    func toggleMonitoring() {
        if recordingState == .idle {
            bufferManager.startMonitoring()
        } else {
            bufferManager.stopMonitoring()
        }
    }
    
    func triggerClip() {
        bufferManager.triggerClip()
    }
    
    func deleteClip(_ clip: ClipInfo) {
        if activeVideoURL == clip.url {
            activeVideoURL = nil
        }
        repository.deleteClip(clip)
    }
    
    /**
     * Tenta ativar o app via Supabase com a licença informada.
     */
    func activateApp(licenseKey: String) {
        guard !licenseKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            self.activationError = "Insira uma chave de licença."
            return
        }
        
        self.isActivating = true
        self.activationError = nil
        
        repository.validateLicenseOnSupabase(licenseKey: licenseKey) { [weak self] result in
            DispatchQueue.main.sync {
                self?.isActivating = false
                switch result {
                case .success:
                    self?.isActivated = true
                case .failure(let error):
                    self?.activationError = error.localizedDescription
                }
            }
        }
    }
    
    private func checkLicenseRemote() {
        repository.checkLicenseStatusOnStartup { [weak self] active in
            DispatchQueue.main.async {
                self?.isActivated = active
            }
        }
    }
    
    func saveToPhotos(clip: ClipInfo) {
        repository.saveToPhotoLibrary(videoURL: clip.url) { [weak self] success in
            DispatchQueue.main.async {
                if success {
                    self?.toastMessage = "Vídeo salvo na Galeria de Fotos!"
                } else {
                    self?.toastMessage = "Erro ao salvar na Galeria (permissão?)."
                }
            }
        }
    }
}
