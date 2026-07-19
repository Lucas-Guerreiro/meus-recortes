import Foundation
import AVFoundation

struct ClipSegment {
    let fileURL: URL
    let startTimeMs: Int64
    let endTimeMs: Int64
}

class VideoTrimmer {
    
    static let shared = VideoTrimmer()
    
    private init() {}
    
    /**
     * Corta e une múltiplos segmentos de arquivos de vídeo MP4 em um único arquivo de destino de forma instantânea.
     */
    func trimAndConcat(segments: [ClipSegment], outputURL: URL, completion: @escaping (Bool, Error?) -> Void) {
        let composition = AVMutableComposition()
        
        guard let compositionVideoTrack = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid),
              let compositionAudioTrack = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) else {
            completion(false, NSError(domain: "VideoTrimmer", code: 0, userInfo: [NSLocalizedDescriptionKey: "Não foi possível criar as trilhas de composição."]))
            return
        }
        
        var accumulativeTime = CMTime.zero
        
        // Configurar a rotação correta baseada no primeiro segmento
        var videoTransform: CGAffineTransform? = nil
        
        for segment in segments {
            guard FileManager.default.fileExists(atPath: segment.fileURL.path) else { continue }
            
            let asset = AVURLAsset(url: segment.fileURL)
            
            // Obter trilhas originais de vídeo e áudio
            guard let assetVideoTrack = asset.tracks(withMediaType: .video).first else { continue }
            let assetAudioTrack = asset.tracks(withMediaType: .audio).first
            
            // Salvar a rotação da câmera (transform) para aplicar no arquivo de saída
            if videoTransform == nil {
                videoTransform = assetVideoTrack.preferredTransform
            }
            
            // Calcular o TimeRange do corte em CMTime
            let startTime = CMTime(value: segment.startTimeMs, timescale: 1000)
            let endTime = CMTime(value: segment.endTimeMs, timescale: 1000)
            let duration = CMTimeSubtract(endTime, startTime)
            let timeRange = CMTimeRange(start: startTime, duration: duration)
            
            do {
                // Inserir trecho de vídeo na composição
                try compositionVideoTrack.insertTimeRange(timeRange, of: assetVideoTrack, at: accumulativeTime)
                
                // Inserir trecho de áudio na composição se existir
                if let audioTrack = assetAudioTrack {
                    try compositionAudioTrack.insertTimeRange(timeRange, of: audioTrack, at: accumulativeTime)
                }
                
                // Incrementar tempo acumulado para colar o próximo segmento na sequência
                accumulativeTime = CMTimeAdd(accumulativeTime, duration)
            } catch {
                completion(false, error)
                return
            }
        }
        
        // Aplicar a rotação correta da câmera na trilha de vídeo final
        if let transform = videoTransform {
            compositionVideoTrack.preferredTransform = transform
        }
        
        // Remover arquivos antigos se existirem
        try? FileManager.default.removeItem(at: outputURL)
        
        // Configurar sessão de exportação rápida (Passthrough copia bits sem re-codificar)
        guard let exportSession = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetPassthrough) else {
            completion(false, NSError(domain: "VideoTrimmer", code: 0, userInfo: [NSLocalizedDescriptionKey: "Falha ao inicializar sessão de exportação."]))
            return
        }
        
        exportSession.outputURL = outputURL
        exportSession.outputFileType = .mp4
        exportSession.shouldOptimizeForNetworkUse = true
        
        exportSession.exportAsynchronously {
            DispatchQueue.main.async {
                switch exportSession.status {
                case .completed:
                    completion(true, nil)
                case .failed:
                    completion(false, exportSession.error)
                case .cancelled:
                    completion(false, NSError(domain: "VideoTrimmer", code: 0, userInfo: [NSLocalizedDescriptionKey: "Exportação cancelada."]))
                default:
                    completion(false, NSError(domain: "VideoTrimmer", code: 0, userInfo: [NSLocalizedDescriptionKey: "Erro de exportação desconhecido."]))
                }
            }
        }
    }
}
