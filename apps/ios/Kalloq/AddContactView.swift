import SwiftUI

/// Contacts are added only by user ID: no directory, no search by name. The ID travels as a QR code
/// or an invite link (`docs/ARQUITECTURA.md` §5).
struct AddContactView: View {
    @EnvironmentObject private var app: AppController
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme

    @State private var typed = ""
    @State private var scanning = false

    var body: some View {
        NavigationStack {
            Form {
                if let invite = app.myInvite, let qr = QRCode.image(invite) {
                    Section {
                        VStack(spacing: 14) {
                            Image(uiImage: qr)
                                .interpolation(.none)
                                .resizable()
                                .scaledToFit()
                                .frame(width: 210, height: 210)
                                .padding(10)
                                .background(.white, in: RoundedRectangle(cornerRadius: 12))

                            Text(invite)
                                .font(.system(.caption, design: .monospaced))
                                .foregroundStyle(.secondary)
                                .textSelection(.enabled)
                                .multilineTextAlignment(.center)

                            ShareLink(item: invite) {
                                Label("Compartir", systemImage: "square.and.arrow.up")
                            }
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                    } header: {
                        Text("Tu código")
                    } footer: {
                        Text("Deja que tu contacto escanee este código o comparte tu enlace.")
                    }
                }

                Section {
                    Button {
                        scanning = true
                    } label: {
                        Label("Escanear código QR", systemImage: "qrcode.viewfinder")
                    }

                    TextField("ID o enlace de tu contacto", text: $typed, axis: .vertical)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.system(.body, design: .monospaced))

                    Button("Enviar solicitud") {
                        app.addFriend(typed)
                        dismiss()
                    }
                    .disabled(typed.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || app.busy)
                } header: {
                    Text("Agregar contacto")
                } footer: {
                    Text("Escanea su código o pega el ID o el enlace que te pasaron.")
                }
            }
            .navigationTitle("Agregar contacto")
            .navigationBarTitleDisplayMode(.inline)
            .sheet(isPresented: $scanning) {
                QRScannerSheet(prompt: "Apunta al código QR de Kalloq de tu contacto") { text in
                    // The same confirmation as an opened link: the code could be anyone's.
                    dismiss()
                    app.openInvite(text)
                }
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancelar") { dismiss() }
                }
            }
        }
    }
}
