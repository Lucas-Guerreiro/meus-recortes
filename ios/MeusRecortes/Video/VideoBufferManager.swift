import Foundation
import AVFoundation

class VideoBufferManager: NSObject, ObservableObject, AVCaptureFileOutputRecordingDelegate {
    
    static let shared = VideoBufferManager()
    
    enum RecordingState: Equatable {
        case idle
        case initializing
        case monitoring
        case clipping(secondsRemaining: Int)
        case saving(progress: String)
    }
    
    @Published var state: RecordingState = .idle
    @Published var eventMessage: String? = nil
    
    private let repository = ReplayRepository.shared
    
    // AVCaptureSession e saídas
    let captureSession = AVCaptureSession()
    private var movieOutput = AVCaptureMovieFileOutput()
    private var activeRecordingURL: URL?
    
    // Arquivos Circulares Temporários
    private lazy var tempFile1: URL = {
        return URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("temp_video_1.mp4")
    }()
    
    private lazy var tempFile2: URL = {
        return URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("temp_video_2.mp4")
    }()
    
    private var isFile1Active = true
    private var currentRecordingStartTime = Date()
    private var previousFileDurationMs: Double = 0.0
    private var previousFile: URL? = nil
    
    private var rotationTimer: Timer?
    private var countdownTimer: Timer?
    
    // Configurações ativas
    private var antesSegundos = 5
    private var depoisSegundos = 5
    
    private var isClippingTriggered = false
    private var cliqueOffsetMs: Double = 0.0
    
    private override init() {
        super.init()
        self.antesSegundos = repository.getAntesSegundos()
        self.depoisSegundos = repository.getDepoisSegundos()
        setupCaptureSession()
    }
    
    func updateConfig(antes: Int, depois: Int) {
        self.antesSegundos = antes
        self.depoisSegundos = depois
        repository.saveConfig(antes: antes, depois: depois)
    }
    
    private func setupCaptureSession() {
        captureSession.beginConfiguration()
        
        // 1. Adicionar dispositivo de vídeo (câmera traseira)
        guard let videoDevice = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let videoInput = try? AVCaptureDeviceInput(device: videoDevice) else {
            print("Erro ao acessar câmera traseira.")
            captureSession.commitConfiguration()
            return
        }
        
        if captureSession.canAddInput(videoInput) {
            captureSession.addInput(videoInput)
        }
        
        // 2. Adicionar dispositivo de áudio (microfone)
        if let audioDevice = AVCaptureDevice.default(for: .audio),
           let audioInput = try? AVCaptureDeviceInput(device: audioDevice) {
            if captureSession.canAddInput(audioInput) {
                captureSession.addInput(audioInput)
            }
        }
        
        // 3. Adicionar saída de gravação de vídeo
        if captureSession.canAddOutput(movieOutput) {
            captureSession.addOutput(movieOutput)
        }
        
        captureSession.commitConfiguration()
    }
    
    func startMonitoring() {
        guard state == .idle else { return }
        
        state = .initializing
        isFile1Active = true
        previousFile = nil
        previousFileDurationMs = 0.0
        isClippingTriggered = false
        
        // Limpar arquivos temporários antigos
        try? FileManager.default.removeItem(at: tempFile1)
        try? FileManager.default.removeItem(at: tempFile2)
        
        if !captureSession.isRunning {
            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                self?.captureSession.startRunning()
                DispatchQueue.main.async {
                    self?.startRecordingOnTempFile()
                }
            }
        } else {
            startRecordingOnTempFile()
        }
    }
    
    func stopMonitoring() {
        rotationTimer?.invalidate()
        countdownTimer?.invalidate()
        
        if movieOutput.isRecording {
            movieOutput.stopRecording()
        }
        
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            if self?.captureSession.isRunning == true {
                self?.captureSession.stopRunning()
            }
            DispatchQueue.main.async {
                try? FileManager.default.removeItem(at: self?.tempFile1 ?? URL(fileURLWithPath: ""))
                try? FileManager.default.removeItem(at: self?.tempFile2 ?? URL(fileURLWithPath: ""))
                self?.previousFile = nil
                self?.state = .idle
            }
        }
    }
    
    func triggerClip() {
        guard state == .monitoring else { return }
        
        let cliqueTime = Date()
        cliqueOffsetMs = cliqueTime.timeIntervalSince(currentRecordingStartTime) * 1000.0
        
        // Parar a rotação automática para manter a gravação focada no trecho pós-clique
        rotationTimer?.invalidate()
        isClippingTriggered = true
        
        var secondsLeft = depoisSegundos
        state = .clipping(secondsRemaining: secondsLeft)
        
        countdownTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] timer in
            guard let self = self else { return }
            secondsLeft -= 1
            if secondsLeft > 0 {
                self.state = .clipping(secondsRemaining: secondsLeft)
            } else {
                timer.invalidate()
                self.state = .saving(progress: "Processando replay...")
                self.movieOutput.stopRecording() // Para a gravação, acionando o delegado didFinishRecordingTo
            }
        }
    }
    
    private func startRecordingOnTempFile() {
        let targetURL = isFile1Active ? tempFile1 : tempFile2
        
        // Remove arquivo antigo no mesmo slot se existir
        try? FileManager.default.removeItem(at: targetURL)
        
        activeRecordingURL = targetURL
        movieOutput.startRecording(to: targetURL, recordingDelegate: self)
    }
    
    private func scheduleRotation() {
        rotationTimer?.invalidate()
        rotationTimer = Timer.scheduledTimer(withTimeInterval: 45.0, repeats: false) { [weak self] _ in
            guard let self = self, self.state == .monitoring else { return }
            
            let currentURL = self.isFile1Active ? self.tempFile1 : self.tempFile2
            self.previousFileDurationMs = Date().timeIntervalSince(self.currentRecordingStartTime) * 1000.0
            self.previousFile = currentURL
            
            // Alternar arquivos
            self.isFile1Active.toggle()
            
            // Iniciar nova gravação no outro slot
            self.startRecordingOnTempFile()
        }
    }
    
    // --- AVCAPTUREFILEOUTPUTRECORDINGDELEGATE ---
    
    func fileOutput(_ output: AVCaptureFileOutput, didStartRecordingTo fileURL: URL, from connections: [AVCaptureConnection]) {
        currentRecordingStartTime = Date()
        
        if state == .initializing {
            state = .monitoring
        }
        
        if state == .monitoring {
            scheduleRotation()
        }
    }
    
    func fileOutput(_ output: AVCaptureFileOutput, didFinishRecordingTo outputFileURL: URL, from connections: [AVCaptureConnection], error: Error?) {
        if let error = error as NSError?, error.code != Int(AVError.maximumDurationReached.rawValue) && error.code != Int(AVError.maximumSizeReached.rawValue) {
            // Ignora avisos normais de interrupção forçada
            print("Erro ao gravar arquivo temporário de vídeo: \(error.localizedDescription)")
        }
        
        // Se a parada foi solicitada pelo gatilho do Replay, processamos o corte
        if isClippingTriggered {
            isClippingTriggered = false
            processAndSaveClip(currentFile: outputFileURL)
        }
    }
    
    private func processAndSaveClip(currentFile: URL) {
        let antesMs = Double(antesSegundos) * 1000.0
        let depoisMs = Double(depoisSegundos) * 1000.0
        
        var segments: [ClipSegment] = []
        
        // 1. Calcular de onde extrair as partes do vídeo
        if cliqueOffsetMs >= antesMs {
            // O vídeo anterior cabe todo no arquivo atual
            let startClipMs = Int64(cliqueOffsetMs - antesMs)
            let endClipMs = Int64(cliqueOffsetMs + depoisMs)
            segments.append(ClipSegment(fileURL: currentFile, startTimeMs: startClipMs, endTimeMs: endClipMs))
        } else {
            // Vídeo composto: Parte do arquivo anterior é necessária
            let faltaMs = antesMs - cliqueOffsetMs
            
            if let prevFile = previousFile, FileManager.default.fileExists(atPath: prevFile.path) {
                let asset = AVURLAsset(url: prevFile)
                let durationMs = CMTimeGetSeconds(asset.duration) * 1000.0
                let prevStartMs = Int64(max(0.0, durationMs - faltaMs))
                
                segments.append(ClipSegment(fileURL: prevFile, startTimeMs: prevStartMs, endTimeMs: Int64(durationMs)))
            }
            
            // Adicionar a parte do arquivo atual
            let currentEndMs = Int64(cliqueOffsetMs + depoisMs)
            segments.append(ClipSegment(fileURL: currentFile, startTimeMs: 0, endTimeMs: currentEndMs))
        }
        
        // 2. Definir destino final do clipe
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMMdd_HHmmss"
        let timestamp = formatter.string(from: Date())
        let fileName = "REPLAY_\(timestamp).mp4"
        let finalURL = repository.clipsDirectory.appendingPathComponent(fileName)
        
        // 3. Chamar o Trimmer
        VideoTrimmer.shared.trimAndConcat(segments: segments, outputURL: finalURL) { [weak self] success, error in
            guard let self = self else { return }
            
            if success {
                self.repository.loadSavedClips()
                self.eventMessage = "Replay salvo com sucesso!"
            } else {
                print("Erro ao cortar vídeo: \(String(describing: error))")
                self.eventMessage = "Falha ao recortar replay."
            }
            
            // Apagar temporários e resetar
            try? FileManager.default.removeItem(at: self.tempFile1)
            try? FileManager.default.removeItem(at: self.tempFile2)
            self.previousFile = nil
            
            // Reiniciar gravação do buffer circular
            self.state = .initializing
            self.isFile1Active = true
            self.startRecordingOnTempFile()
        }
    }
}
