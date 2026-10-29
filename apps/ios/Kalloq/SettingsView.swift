import SwiftUI

/// Account and security, as in Android's settings screen. Language (ES/EN) arrives with the
/// localisation step; push with APNs after that.
struct SettingsView: View {
    @EnvironmentObject private var app: AppController
    @Environment(\.dismiss) private var dismiss

    @State private var changingPassword = false
    @State private var newRecovery = false
    @State private var confirmRegenId = false
    @State private var confirmDelete = false
    @State private var password = ""
    @State private var newPassword = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("Cuenta") {
                    LabeledContent("Usuario", value: app.username ?? "—")
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Mi ID").font(.footnote).foregroundStyle(.secondary)
                        Text(app.myId ?? "—")
                            .font(.system(.footnote, design: .monospaced))
                            .textSelection(.enabled)
                    }
                    Button("Nuevo ID") { confirmRegenId = true }
                }

                Section {
                    LabeledContent("Recibir llamadas", value: app.pushReady ? "Con la app cerrada" : "Solo con la app abierta")
                } footer: {
                    if !app.pushReady {
                        Text("Este teléfono todavía no puede recibir llamadas con Kalloq cerrada.")
                    }
                }

                Section("Seguridad") {
                    Button("Cambiar contraseña") {
                        password = ""
                        newPassword = ""
                        changingPassword = true
                    }
                    Button("Nuevo código de recuperación") {
                        password = ""
                        newRecovery = true
                    }
                    NavigationLink("Usuarios bloqueados") { BlockedView() }
                    NavigationLink("Comprobación de cifrado") { CryptoCheckView() }
                }

                Section {
                    Button("Cerrar sesión") { app.logout() }
                    Button("Eliminar cuenta", role: .destructive) {
                        password = ""
                        confirmDelete = true
                    }
                }

                Section("Acerca de") {
                    LabeledContent("Versión", value: Bundle.main.version)
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Servidor").font(.footnote).foregroundStyle(.secondary)
                        Text(app.serverURL).font(.system(.footnote, design: .monospaced))
                    }
                    ConnectionLabel(state: app.conn)
                }
            }
            .navigationTitle("Ajustes")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Listo") { dismiss() }
                }
            }
            .alert("Cambiar contraseña", isPresented: $changingPassword) {
                SecureField("Contraseña actual", text: $password)
                SecureField("Nueva contraseña", text: $newPassword)
                Button("Confirmar") { app.changePassword(current: password, new: newPassword) }
                Button("Cancelar", role: .cancel) {}
            }
            .alert("Nuevo código de recuperación", isPresented: $newRecovery) {
                SecureField("Contraseña", text: $password)
                Button("Confirmar") {
                    app.regenRecovery(password: password)
                    dismiss()
                }
                Button("Cancelar", role: .cancel) {}
            } message: {
                Text("El código anterior deja de servir.")
            }
            .confirmationDialog("¿Nuevo ID?", isPresented: $confirmRegenId, titleVisibility: .visible) {
                Button("Nuevo ID") { app.regenId() }
            } message: {
                Text("Tus contactos te conservan, pero quien tenga tu ID o código QR anterior ya no podrá encontrarte.")
            }
            .alert("Eliminar cuenta", isPresented: $confirmDelete) {
                SecureField("Contraseña", text: $password)
                Button("Eliminar", role: .destructive) {
                    app.deleteAccount(password: password)
                    dismiss()
                }
                Button("Cancelar", role: .cancel) {}
            } message: {
                Text("Tu cuenta y tu lista de contactos se borran del servidor. No se puede deshacer.")
            }
        }
    }
}

private struct BlockedView: View {
    @EnvironmentObject private var app: AppController

    var body: some View {
        List {
            if app.blocked.isEmpty {
                Text("Ninguno").foregroundStyle(.secondary)
            }
            ForEach(app.blocked, id: \.self) { id in
                HStack {
                    Text(String(id.prefix(8))).font(.system(.body, design: .monospaced))
                    Spacer()
                    Button("Desbloquear") { app.unblock(id) }
                        .buttonStyle(.borderless)
                }
            }
        }
        .navigationTitle("Usuarios bloqueados")
        .navigationBarTitleDisplayMode(.inline)
    }
}

extension Bundle {
    var version: String {
        let name = infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        let build = infoDictionary?["CFBundleVersion"] as? String ?? "?"
        return "\(name) (\(build))"
    }
}
