import SwiftUI

/// Sign in, create an account, or recover one with the offline code. There is no email anywhere:
/// the recovery code is the only way back in (`docs/ARQUITECTURA.md` §3).
struct AuthView: View {
    @EnvironmentObject private var app: AppController
    @Environment(\.colorScheme) private var scheme

    @State private var creating = false
    @State private var username = ""
    @State private var password = ""
    @State private var confirmation = ""
    @State private var showPassword = false
    @State private var recovering = false

    private var usernameOK: Bool { Validation.username(username) }
    private var passwordOK: Bool { password.count >= 10 }
    private var canSubmit: Bool {
        usernameOK && passwordOK && (!creating || password == confirmation) && !app.busy
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 22) {
                    VStack(spacing: 10) {
                        BrandMark(size: 52)
                        Text("Kalloq").font(.largeTitle.bold())
                        Text("Llamadas de voz privadas, cifradas de extremo a extremo")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .padding(.top, 28)

                    Picker("", selection: $creating) {
                        Text("Iniciar sesión").tag(false)
                        Text("Crear cuenta").tag(true)
                    }
                    .pickerStyle(.segmented)

                    VStack(spacing: 14) {
                        Field(title: "Usuario", hint: creating ? "6–32 caracteres: a–z, 0–9, _ y . — solo tú lo ves" : nil) {
                            TextField("Usuario", text: $username)
                                .textContentType(.username)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                        }

                        Field(title: "Contraseña", hint: creating ? "Mínimo 10 caracteres" : nil) {
                            PasswordField(text: $password, visible: $showPassword, placeholder: "Contraseña")
                        }

                        if creating {
                            Field(title: "Confirmar contraseña",
                                  hint: confirmation.isEmpty || password == confirmation ? nil : "No coinciden") {
                                PasswordField(text: $confirmation, visible: $showPassword, placeholder: "Confirmar contraseña")
                            }
                        }
                    }

                    Button(action: submit) {
                        HStack {
                            if app.busy { ProgressView().tint(.white) }
                            Text(creating ? "Crear cuenta" : "Iniciar sesión")
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Color.kalloq(scheme))
                    .disabled(!canSubmit)

                    if !creating {
                        Button("¿Olvidaste tu contraseña?") { recovering = true }
                            .font(.footnote)
                    }
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 32)
            }
            .sheet(isPresented: $recovering) { RecoverView() }
        }
    }

    private func submit() {
        if creating {
            app.register(username: username, password: password)
        } else {
            app.login(username: username, password: password)
        }
    }
}

/// Username and password as the server validates them (`docs/PROTOCOLO.md`), checked here first so
/// a typo does not cost a round trip.
enum Validation {
    static func username(_ value: String) -> Bool {
        let name = value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard (6...32).contains(name.count) else { return false }
        return name.allSatisfy { $0.isASCII && ($0.isLowercase && $0.isLetter || $0.isNumber || $0 == "_" || $0 == ".") }
    }
}

struct Field<Content: View>: View {
    let title: LocalizedStringKey
    var hint: LocalizedStringKey?
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title).font(.footnote).foregroundStyle(.secondary)
            content
                .textFieldStyle(.plain)
                .padding(11)
                .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 10))
            if let hint {
                Text(hint).font(.caption2).foregroundStyle(.secondary)
            }
        }
    }
}

struct PasswordField: View {
    @Binding var text: String
    @Binding var visible: Bool
    let placeholder: LocalizedStringKey

    var body: some View {
        HStack {
            Group {
                if visible {
                    TextField(placeholder, text: $text)
                } else {
                    SecureField(placeholder, text: $text)
                }
            }
            .textContentType(.password)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()

            Button {
                visible.toggle()
            } label: {
                Image(systemName: visible ? "eye.slash" : "eye")
            }
            .buttonStyle(.plain)
            .foregroundStyle(.secondary)
            .accessibilityLabel(visible ? Text("Ocultar contraseña") : Text("Mostrar contraseña"))
        }
    }
}

/// Recovery: username + offline code + a new password. The server then issues a new code.
private struct RecoverView: View {
    @EnvironmentObject private var app: AppController
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme

    @State private var username = ""
    @State private var code = ""
    @State private var password = ""
    @State private var showPassword = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Usuario", text: $username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("Código de recuperación", text: $code)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .font(.system(.body, design: .monospaced))
                    PasswordField(text: $password, visible: $showPassword, placeholder: "Nueva contraseña")
                } footer: {
                    Text("El código de 8 grupos que te dimos al crear la cuenta. Los guiones y las mayúsculas no importan.")
                }

                Section {
                    Button("Recuperar") {
                        app.recover(username: username, code: code, newPassword: password)
                        dismiss()
                    }
                    .disabled(!Validation.username(username) || code.count < 8 || password.count < 10)
                }
            }
            .navigationTitle("Recuperar cuenta")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Atrás") { dismiss() }
                }
            }
        }
    }
}
