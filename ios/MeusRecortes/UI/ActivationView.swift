import SwiftUI

struct ActivationView: View {
    @ObservedObject var viewModel: MainViewModel
    @State private var licenseInput = ""
    @State private var isCopied = false
    
    var body: some View {
        ZStack {
            // Fundo Gradiente Roxo/Carvão
            LinearGradient(
                gradient: Gradient(colors: [Color(red: 0.16, green: 0.08, blue: 0.27), Color(red: 0.07, green: 0.07, blue: 0.07)]),
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()
            
            VStack(spacing: 24) {
                Spacer()
                
                // Título e Ícone
                VStack(spacing: 12) {
                    Text("🔑")
                        .font(.system(size: 50))
                    
                    Text("Ativação da Licença")
                        .font(.title)
                        .fontWeight(.black)
                        .foregroundColor(.white)
                    
                    Text("Insira sua chave de licença Supabase para desbloquear o aplicativo de replays.")
                        .font(.subheadline)
                        .foregroundColor(.gray)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 20)
                }
                
                Spacer().frame(height: 16)
                
                // Card do Device ID
                VStack(alignment: .leading, spacing: 6) {
                    Text("ID DO DISPOSITIVO")
                        .font(.caption2)
                        .fontWeight(.bold)
                        .foregroundColor(.gray)
                    
                    HStack {
                        Text(viewModel.deviceId)
                            .font(.headline)
                            .fontWeight(.bold)
                            .foregroundColor(.white)
                        
                        Spacer()
                        
                        Button(action: {
                            UIPasteboard.general.string = viewModel.deviceId
                            isCopied = true
                            DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
                                isCopied = false
                            }
                        }) {
                            Text(isCopied ? "Copiado! ✓" : "Copiar")
                                .font(.caption)
                                .fontWeight(.bold)
                                .foregroundColor(.white)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 8)
                                .background(Color(red: 0.29, green: 0.08, blue: 0.55))
                                .cornerRadius(8)
                        }
                    }
                    .padding(14)
                    .background(Color.black.opacity(0.4))
                    .cornerRadius(12)
                    .overlay(
                        RoundedRectangle(cornerRadius: 12)
                            .border(Color.white.opacity(0.1), width: 1)
                    )
                }
                .padding(.horizontal, 24)
                
                // Input de Texto da Licença
                VStack(alignment: .leading, spacing: 8) {
                    TextField("REPLAY-XXXX-XXXX", text: $licenseInput)
                        .autocapitalization(.allCharacters)
                        .disableAutocorrection(true)
                        .padding()
                        .foregroundColor(.white)
                        .background(Color.black.opacity(0.2))
                        .cornerRadius(12)
                        .overlay(
                            RoundedRectangle(cornerRadius: 12)
                                .stroke(Color.white.opacity(0.2), width: 1)
                        )
                    
                    if let error = viewModel.activationError {
                        Text(error)
                            .font(.footnote)
                            .fontWeight(.semibold)
                            .foregroundColor(Color(red: 0.9, green: 0.22, blue: 0.21))
                            .multilineTextAlignment(.center)
                            .frame(maxWidth: .infinity)
                            .padding(.top, 4)
                    }
                }
                .padding(.horizontal, 24)
                
                Spacer().frame(height: 12)
                
                // Botão de Ativação
                Button(action: {
                    viewModel.activateApp(licenseKey: licenseInput)
                }) {
                    HStack {
                        if viewModel.isActivating {
                            ProgressView()
                                .progressViewStyle(CircularProgressViewStyle(tint: .white))
                        } else {
                            Text("ATIVAR APLICATIVO")
                                .font(.subheadline)
                                .fontWeight(.bold)
                                .foregroundColor(.white)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 50)
                    .background(viewModel.isActivating ? Color.gray : Color(red: 0.73, green: 0.41, blue: 0.78))
                    .cornerRadius(12)
                    .shadow(radius: 4)
                }
                .disabled(viewModel.isActivating)
                .padding(.horizontal, 24)
                
                Spacer()
                
                Text("Suporte para comercialização: comercial@exemplo.com")
                    .font(.caption2)
                    .foregroundColor(.gray)
                    .padding(.bottom, 12)
            }
        }
    }
}

struct ActivationView_Previews: PreviewProvider {
    static var previews: some View {
        ActivationView(viewModel: MainViewModel())
    }
}
