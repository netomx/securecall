import SwiftUI

/// Both people open this screen for each other, scan each other's QR and compare the resulting
/// code, as Android's `VerifyScreen`. See `docs/ARQUITECTURA.md` §4.2.
struct VerifyView: View {
    @EnvironmentObject private var app: AppController
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme

    let id: String

    @State private var generation = 0  // bumped by "Start again", for a new QR
    @State private var scanning = false

    private var friend: Friend? { app.friends.first { $0.id == id } }

    var body: some View {
        ScrollView {
            if let friend {
                VStack(spacing: 20) {
                    if let code = friend.verifyCode {
                        VStack(spacing: 8) {
                            Image(systemName: "checkmark.shield.fill")
                                .font(.largeTitle)
                                .foregroundStyle(Color.kalloq(scheme))
                            Text("Los dos teléfonos deben mostrar este código:")
                                .multilineTextAlignment(.center)
                            Text(code)
                                .font(.system(.title, design: .monospaced).weight(.semibold))
                                .textSelection(.enabled)
                            Text("Si es distinto, alguno escaneó un código viejo: toquen Empezar de nuevo en ambos teléfonos.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                                .multilineTextAlignment(.center)
                        }
                        .padding()
                        .frame(maxWidth: .infinity)
                        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 14))
                    }

                    VStack(alignment: .leading, spacing: 12) {
                        Text("1. Que \(friend.displayName) escanee este código con su Kalloq (Contactos → tu nombre → Verificar con QR).")
                        if let text = app.verifyQrText(id), let qr = QRCode.image(text) {
                            Image(uiImage: qr)
                                .interpolation(.none)
                                .resizable()
                                .scaledToFit()
                                .frame(maxWidth: 260)
                                .padding(10)
                                .background(.white, in: RoundedRectangle(cornerRadius: 12))
                                .frame(maxWidth: .infinity)
                                .id(generation)
                        }
                        Text("Muestra este código solo en persona: no envíes una foto de él.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity)
                            .multilineTextAlignment(.center)
                    }

                    Divider()

                    VStack(alignment: .leading, spacing: 12) {
                        Text("2. Escanea el código en el teléfono de \(friend.displayName).")
                        Button {
                            scanning = true
                        } label: {
                            Label("Escanear su código", systemImage: "qrcode.viewfinder")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.large)
                        .tint(Color.kalloq(scheme))
                    }

                    if friend.verified {
                        HStack(spacing: 12) {
                            Button("Empezar de nuevo") {
                                app.resetVerification(id)
                                generation += 1
                            }
                            .buttonStyle(.bordered)
                            Button("Quitar verificación", role: .destructive) {
                                app.resetVerification(id)
                                dismiss()
                            }
                        }
                        .padding(.top, 8)
                    }
                }
                .padding()
            }
        }
        .navigationTitle("Verificar en persona")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $scanning) {
            QRScannerSheet(prompt: "Apunta al QR de verificación en el teléfono de tu contacto") { text in
                app.scanVerify(id, text)
            }
        }
    }
}
