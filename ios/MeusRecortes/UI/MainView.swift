import SwiftUI
import AVFoundation
import AVKit

// Bridge para envolver a AVCaptureVideoPreviewLayer no SwiftUI
struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession
    
    func makeUIView(context: Context) -> UIView {
        let view = UIView(frame: UIScreen.main.bounds)
        let previewLayer = AVCaptureVideoPreviewLayer(session: session)
        previewLayer.frame = view.bounds
        previewLayer.videoGravity = .resizeAspectFill
        view.layer.addSublayer(previewLayer)
        return view
    }
    
    func updateUIView(_ uiView: UIView, context: Context) {
        if let sublayers = uiView.layer.sublayers,
           let previewLayer = sublayers.first as? AVCaptureVideoPreviewLayer {
            previewLayer.frame = uiView.bounds
        }
    }
}

struct MainView: View {
    @StateObject private var viewModel = MainViewModel()
    @State private var showSettings = false
    @State private var showGallery = false
    
    var body: some View {
        ZStack {
            if !viewModel.isActivated {
                // Tela de ativação se não licenciado
                ActivationView(viewModel: viewModel)
            } else {
                // Interface da Câmera principal
                ZStack {
                    // 1. Visor da Câmera Real (ou Fundo Cinza no Simulador Mac)
                    CameraPreview(session: VideoBufferManager.shared.captureSession)
                        .ignoresSafeArea()
                        .background(Color.black)
                    
                    // 2. Gradiente de Escurecimento do HUD
                    VStack {
                        LinearGradient(gradient: Gradient(colors: [.black.opacity(0.4), .clear]), startPoint: .top, endPoint: .bottom)
                            .frame(height: 120)
                        Spacer()
                        LinearGradient(gradient: Gradient(colors: [.clear, .black.opacity(0.6)]), startPoint: .top, endPoint: .bottom)
                            .frame(height: 150)
                    }
                    .ignoresSafeArea()
                    
                    // 3. HUD Superior (Engrenagem + Status do Buffer)
                    VStack {
                        HStack {
                            // Botão de Engrenagem (Configurações)
                            Button(action: { showSettings = true }) {
                                Text("⚙️")
                                    .font(.title2)
                                    .padding(10)
                                    .background(Color.black.opacity(0.6))
                                    .clipShape(Circle())
                                    .overlay(Circle().stroke(Color.white.opacity(0.2), width: 1))
                            }
                            
                            Spacer()
                            
                            // Chip central de status
                            HStack(spacing: 6) {
                                let isMonitoring = viewModel.recordingState == .monitoring || 
                                                   ifCaseClipping(viewModel.recordingState)
                                
                                Circle()
                                    .fill(isMonitoring ? Color(red: 0.73, green: 0.41, blue: 0.78) : Color.gray)
                                    .frame(width: 8, height: 8)
                                    .scaleEffect(isMonitoring ? 1.2 : 1.0)
                                
                                Text(isMonitoring ? "BUFFER ATIVO (\(viewModel.antesSegundos)s + \(viewModel.depoisSegundos)s)" : "BUFFER DESLIGADO")
                                    .font(.caption2)
                                    .fontWeight(.bold)
                                    .foregroundColor(isMonitoring ? Color(red: 0.88, green: 0.75, blue: 0.91) : .lightGray)
                                    .tracking(0.5)
                            }
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .background(Color.black.opacity(0.6))
                            .cornerRadius(20)
                            .overlay(RoundedRectangle(cornerRadius: 20).stroke(Color.white.opacity(0.15), width: 1))
                            
                            Spacer()
                            Spacer().frame(width: 44) // Equilíbrio óptico
                        }
                        .padding(.top, 54)
                        .padding(.horizontal, 20)
                        
                        Spacer()
                    }
                    
                    // 4. HUD Inferior (Galeria + Botão Replay + Ligar/Desligar Buffer)
                    VStack {
                        Spacer()
                        
                        HStack(spacing: 40) {
                            
                            // Botão Galeria (Esquerda)
                            Button(action: { showGallery = true }) {
                                Text("🎬")
                                    .font(.title)
                                    .frame(width: 56, height: 56)
                                    .background(Color.black.opacity(0.6))
                                    .clipShape(Circle())
                                    .overlay(Circle().stroke(Color.white.opacity(0.2), width: 1))
                            }
                            
                            // Botão Principal Pulsante (Centro)
                            MainActionButtonView(viewModel: viewModel)
                            
                            // Botão Ligar/Desligar Gravação (Direita)
                            let isMonitoring = viewModel.recordingState != .idle
                            Button(action: { viewModel.toggleMonitoring() }) {
                                Text(isMonitoring ? "⏹️" : "📹")
                                    .font(.title2)
                                    .frame(width: 56, height: 56)
                                    .background(isMonitoring ? Color.red.opacity(0.8) : Color.black.opacity(0.6))
                                    .clipShape(Circle())
                                    .overlay(Circle().stroke(Color.white.opacity(0.2), width: 1))
                            }
                        }
                        .padding(.bottom, 36)
                        .padding(.horizontal, 24)
                    }
                }
                .foregroundColor(.white)
            }
        }
        // Exibição do Diálogo de Configurações
        .sheet(isPresented: $showSettings) {
            SettingsSheet(viewModel: viewModel)
        }
        // Exibição da Galeria Deslizante
        .sheet(isPresented: $showGallery) {
            GallerySheet(viewModel: viewModel)
        }
        // Player de Vídeo em Dialog
        .fullScreenCover(item: Binding<ClipInfo?>(
            get: { viewModel.activeVideoURL != nil ? ClipInfo(id: UUID(), url: viewModel.activeVideoURL!, displayName: "", duration: 0, sizeBytes: 0, dateAdded: Date()) : nil },
            set: { viewModel.activeVideoURL = $0?.url }
        )) { clip in
            VideoPlayerView(url: clip.url) {
                viewModel.activeVideoURL = nil
            }
        }
        // Observador de Mensagens Rápidas (Toasts)
        .onChange(of: viewModel.toastMessage) { message in
            if let msg = message {
                // No iOS, podemos simular um toast rápido exibindo um alerta ou imprimindo
                print("TOAST: \(msg)")
                viewModel.toastMessage = nil
            }
        }
    }
    
    // Função utilitária em Swift para verificar se o estado enum é Clipping
    private func ifCaseClipping(_ state: VideoBufferManager.RecordingState) -> Bool {
        if case .clipping = state { return true }
        return false
    }
}

/**
 * Componente do Botão Central Reativo (com animação de pulsação)
 */
struct MainActionButtonView: View {
    @ObservedObject var viewModel: MainViewModel
    @State private var isPulsing = false
    
    var body: some View {
        switch viewModel.recordingState {
        case .idle:
            Button(action: { viewModel.toggleMonitoring() }) {
                Text("LIGAR GRAVAÇÃO")
                    .font(.caption)
                    .fontWeight(.bold)
                    .foregroundColor(.white)
                    .frame(width: 150, height: 50)
                    .background(Color(red: 0.29, green: 0.08, blue: 0.55))
                    .clipShape(Capsule())
                    .shadow(radius: 6)
            }
            
        case .initializing:
            ProgressView()
                .progressViewStyle(CircularProgressViewStyle(tint: .white))
                .frame(width: 76, height: 76)
                .background(Color.gray)
                .clipShape(Circle())
            
        case .monitoring:
            ZStack {
                // Círculo de pulso em degradê roxo/rosa
                Circle()
                    .fill(RadialGradient(gradient: Gradient(colors: [Color(red: 0.88, green: 0.25, blue: 0.98).opacity(0.5), .clear]), center: .center, startRadius: 0, endRadius: 40))
                    .frame(width: 90, height: 90)
                    .scaleEffect(isPulsing ? 1.2 : 1.0)
                    .onAppear {
                        withAnimation(Animation.linear(duration: 0.8).repeatForever(autoreverses: true)) {
                            isPulsing = true
                        }
                    }
                
                Button(action: { viewModel.triggerClip() }) {
                    VStack(spacing: 2) {
                        Text("LANCE!")
                            .font(.system(size: 14, weight: .black))
                        Text("REPLAY")
                            .font(.system(size: 9, weight: .bold))
                            .opacity(0.8)
                    }
                    .foregroundColor(.white)
                    .frame(width: 76, height: 76)
                    .background(LinearGradient(gradient: Gradient(colors: [Color(red: 0.84, green: 0.0, blue: 0.98), Color(red: 0.96, green: 0.0, blue: 0.34)]), startPoint: .topLeading, endPoint: .bottomTrailing))
                    .clipShape(Circle())
                    .shadow(radius: 6)
                }
            }
            
        case .clipping(let secondsRemaining):
            ZStack {
                Circle()
                    .stroke(Color.yellow, lineWidth: 4)
                    .frame(width: 76, height: 76)
                
                VStack(spacing: 2) {
                    Text("\(secondsRemaining)s")
                        .font(.system(size: 22, weight: .black))
                        .foregroundColor(.yellow)
                    Text("GRAVANDO")
                        .font(.system(size: 7, weight: .bold))
                        .foregroundColor(.lightGray)
                }
                .frame(width: 70, height: 70)
                .background(Color(red: 0.15, green: 0.2, blue: 0.22))
                .clipShape(Circle())
            }
            
        case .saving(let progress):
            ZStack {
                ProgressView()
                    .progressViewStyle(CircularProgressViewStyle(tint: Color.green))
                    .frame(width: 76, height: 76)
                
                Text("SALVANDO")
                    .font(.system(size: 9, weight: .bold))
                    .foregroundColor(Color.green)
                    .frame(width: 70, height: 70)
                    .background(Color(red: 0.13, green: 0.13, blue: 0.13))
                    .clipShape(Circle())
            }
        }
    }
}

/**
 * View da Galeria de Replays (Sheet Modal)
 */
struct GallerySheet: View {
    @ObservedObject var viewModel: MainViewModel
    @Environment(\.dismiss) var dismiss
    
    var body: some View {
        NavigationView {
            ZStack {
                Color(red: 0.12, green: 0.12, blue: 0.12).ignoresSafeArea()
                
                if viewModel.savedClips.isEmpty {
                    VStack(spacing: 12) {
                        Text("🎬")
                            .font(.system(size: 40))
                        Text("Nenhum replay salvo ainda.")
                            .font(.headline)
                        Text("Ligue a gravação de buffer e salve seus melhores lances!")
                            .font(.subheadline)
                            .foregroundColor(.gray)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 24)
                    }
                    .foregroundColor(.white)
                } else {
                    List {
                        ForEach(viewModel.savedClips) { clip in
                            HStack(spacing: 12) {
                                // Ícone
                                Text("⚽")
                                    .font(.title2)
                                    .frame(width: 40, height: 40)
                                    .background(Color(red: 0.22, green: 0.28, blue: 0.31))
                                    .cornerRadius(8)
                                
                                // Dados
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(clip.displayName)
                                        .font(.system(size: 13, weight: .bold))
                                        .foregroundColor(.white)
                                        .lineLimit(1)
                                    
                                    let sizeMb = Double(clip.sizeBytes) / (1024.0 * 1024.0)
                                    Text("Duração: \(Int(clip.duration))s | Tamanho: \(String(format: "%.1f", sizeMb)) MB")
                                        .font(.system(size: 11))
                                        .foregroundColor(.gray)
                                }
                                
                                Spacer()
                                
                                // Ações
                                Button(action: { viewModel.activeVideoURL = clip.url }) {
                                    Text("▶️")
                                }
                                .buttonStyle(BorderlessButtonStyle())
                                
                                Button(action: { viewModel.saveToPhotos(clip: clip) }) {
                                    Text("📤")
                                }
                                .buttonStyle(BorderlessButtonStyle())
                                
                                Button(action: { viewModel.deleteClip(clip) }) {
                                    Text("🗑️")
                                }
                                .buttonStyle(BorderlessButtonStyle())
                            }
                            .listRowBackground(Color(red: 0.16, green: 0.16, blue: 0.16))
                        }
                    }
                    .background(Color.clear)
                    .listStyle(PlainListStyle())
                }
            }
            .navigationTitle("Replays Salvos")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Fechar") {
                        dismiss()
                    }
                    .foregroundColor(Color(red: 0.73, green: 0.41, blue: 0.78))
                }
            }
            .preferredColorScheme(.dark)
        }
    }
}

/**
 * Diálogo de Configuração de Tempos (Sheet)
 */
struct SettingsSheet: View {
    @ObservedObject var viewModel: MainViewModel
    @Environment(\.dismiss) var dismiss
    
    var body: some View {
        NavigationView {
            ZStack {
                Color(red: 0.12, green: 0.12, blue: 0.12).ignoresSafeArea()
                
                VStack(spacing: 24) {
                    Text("Configurações do Replay ⚙️")
                        .font(.title3)
                        .fontWeight(.bold)
                        .foregroundColor(.white)
                    
                    Spacer().frame(height: 10)
                    
                    // Slider Antes
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text("Tempo Antes (Passado)")
                                .foregroundColor(.lightGray)
                                .font(.caption)
                            Spacer()
                            Text("\(viewModel.antesSegundos)s")
                                .font(.subheadline)
                                .fontWeight(.bold)
                                .foregroundColor(Color(red: 0.73, green: 0.41, blue: 0.78))
                        }
                        
                        Slider(value: Binding<Double>(
                            get: { Double(viewModel.antesSegundos) },
                            set: { viewModel.setAntesSegundos(Int($0)) }
                        ), in: 1...30, step: 1)
                        .accentColor(Color(red: 0.73, green: 0.41, blue: 0.78))
                    }
                    
                    // Slider Depois
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text("Tempo Depois (Futuro)")
                                .foregroundColor(.lightGray)
                                .font(.caption)
                            Spacer()
                            Text("\(viewModel.depoisSegundos)s")
                                .font(.subheadline)
                                .fontWeight(.bold)
                                .foregroundColor(Color(red: 0.3, green: 0.71, blue: 0.67))
                        }
                        
                        Slider(value: Binding<Double>(
                            get: { Double(viewModel.depoisSegundos) },
                            set: { viewModel.setDepoisSegundos(Int($0)) }
                        ), in: 1...30, step: 1)
                        .accentColor(Color(red: 0.3, green: 0.71, blue: 0.67))
                    }
                    
                    Spacer().frame(height: 8)
                    
                    Text("Total do Clipe: \(viewModel.antesSegundos + viewModel.depoisSegundos) segundos")
                        .font(.subheadline)
                        .fontWeight(.semibold)
                        .foregroundColor(.white)
                    
                    Spacer()
                    
                    Button(action: { dismiss() }) {
                        Text("SALVAR & FECHAR")
                            .font(.headline)
                            .fontWeight(.bold)
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .frame(height: 48)
                            .background(Color(red: 0.73, green: 0.41, blue: 0.78))
                            .cornerRadius(12)
                    }
                }
                .padding(24)
            }
            .navigationBarHidden(true)
            .preferredColorScheme(.dark)
        }
    }
}

/**
 * View do Player de Vídeo Nativo do iOS
 */
struct VideoPlayerView: View {
    let url: URL
    let onDismiss: () -> Void
    
    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            
            VideoPlayer(player: AVPlayer(url: url))
                .ignoresSafeArea()
            
            VStack {
                HStack {
                    Spacer()
                    Button(action: onDismiss) {
                        Text("❌")
                            .font(.headline)
                            .padding(12)
                            .background(Color.black.opacity(0.5))
                            .clipShape(Circle())
                    }
                    .padding(.top, 24)
                    .padding(.trailing, 24)
                }
                Spacer()
            }
            .foregroundColor(.white)
        }
    }
}

// Extensões de Cores simples
extension Color {
    static let lightGray = Color(white: 0.8)
}
extension Text {
    static let lightGray = Color(white: 0.8)
}
