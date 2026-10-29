import SwiftUI

/// One contact: the name only this phone knows, the fingerprint of their identity key, and what to
/// do when that key changes.
struct ContactDetailView: View {
    @EnvironmentObject private var app: AppController
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme

    let id: String

    @State private var alias = ""
    @State private var editingAlias = false
    @State private var confirmRemove = false
    @State private var confirmBlock = false

    private var friend: Friend? { app.friends.first { $0.id == id } }

    var body: some View {
        Form {
            if let friend {
                if friend.keyChanged {
                    Section {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("La llave de este contacto cambió").font(.headline)
                            Text("Puede ser un teléfono nuevo o alguien intentando interceptarte. Confírmalo con tu contacto antes de confiar en ella.")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                            Button("Confiar en la nueva llave") { app.trustKey(id) }
                                .buttonStyle(.bordered)
                                .tint(.red)
                        }
                        .padding(.vertical, 4)
                    }
                }

                Section {
                    Button {
                        alias = friend.alias ?? ""
                        editingAlias = true
                    } label: {
                        HStack {
                            Text("Nombre del contacto")
                            Spacer()
                            (friend.alias.map { Text($0) } ?? Text("Sin nombre"))
                                .foregroundStyle(friend.alias == nil ? .secondary : .primary)
                        }
                    }
                    .tint(.primary)
                } footer: {
                    Text("Solo se guarda en este teléfono.")
                }

                Section("ID") {
                    Text(friend.id)
                        .font(.system(.footnote, design: .monospaced))
                        .textSelection(.enabled)
                }

                Section {
                    (friend.fingerprint.map { Text($0) } ?? Text("Sin llave todavía"))
                        .font(.system(.footnote, design: .monospaced))
                        .textSelection(.enabled)
                } header: {
                    Text("Huella de la llave")
                } footer: {
                    Text("Compárala en persona con tu contacto o, mejor, verifiquen sus QR abajo.")
                }

                Section {
                    NavigationLink {
                        VerifyView(id: id)
                    } label: {
                        Label(friend.verified ? "Verificado en persona" : "Verificar con QR",
                              systemImage: friend.verified ? "checkmark.shield.fill" : "qrcode")
                    }
                    .disabled(friend.keyChanged || friend.pinned == nil)
                } footer: {
                    Text(friend.verified
                         ? "Verificado en persona. Las llamadas usan además una llave que solo conocen sus dos teléfonos."
                         : "Sin verificar: las llamadas usan la llave recibida automáticamente.")
                }

                Section {
                    Button {
                        app.startCall(id)
                    } label: {
                        Label("Llamar", systemImage: "phone.fill")
                    }
                    .disabled(friend.keyChanged || friend.ik == nil || app.call != nil)
                } footer: {
                    if friend.keyChanged {
                        Text("No se puede llamar hasta confirmar la llave nueva.")
                    } else if friend.ik == nil {
                        Text("Este contacto todavía no publicó su llave.")
                    }
                }

                Section {
                    Button("Eliminar contacto", role: .destructive) { confirmRemove = true }
                    Button("Bloquear", role: .destructive) { confirmBlock = true }
                }
            } else {
                Text("Este contacto ya no está en tu lista.")
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle(friend?.displayName ?? "Contacto")
        .navigationBarTitleDisplayMode(.inline)
        .alert("Cambiar nombre", isPresented: $editingAlias) {
            TextField("Nombre del contacto", text: $alias)
            Button("Guardar") { app.setAlias(id, alias) }
            Button("Cancelar", role: .cancel) {}
        }
        .confirmationDialog("¿Eliminar este contacto?", isPresented: $confirmRemove, titleVisibility: .visible) {
            Button("Eliminar", role: .destructive) {
                app.removeFriend(id)
                dismiss()
            }
        }
        .confirmationDialog("¿Bloquear a este usuario? Ya no podrá enviarte solicitudes.",
                            isPresented: $confirmBlock, titleVisibility: .visible) {
            Button("Bloquear", role: .destructive) {
                app.block(id)
                dismiss()
            }
        }
    }
}
