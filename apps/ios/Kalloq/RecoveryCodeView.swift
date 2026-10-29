import SwiftUI

/// The recovery code, shown once. Without email it is the only way back into the account, so the
/// screen does not let the user past it until they say they wrote it down.
struct RecoveryCodeView: View {
    @EnvironmentObject private var app: AppController
    @Environment(\.colorScheme) private var scheme

    let code: String
    @State private var saved = false
    @State private var copied = false

    var body: some View {
        ScrollView {
            VStack(spacing: 22) {
                Image(systemName: "key.horizontal.fill")
                    .font(.system(size: 46))
                    .foregroundStyle(Color.kalloq(scheme))
                    .padding(.top, 40)

                Text("Guarda tu código de recuperación")
                    .font(.title2.bold())
                    .multilineTextAlignment(.center)

                Text("Es la única forma de recuperar tu cuenta si olvidas la contraseña. Solo se muestra una vez: anótalo y guárdalo en un lugar seguro.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)

                Text(code)
                    .font(.system(.title3, design: .monospaced).weight(.semibold))
                    .multilineTextAlignment(.center)
                    .textSelection(.enabled)
                    .padding(18)
                    .frame(maxWidth: .infinity)
                    .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 14))

                Button {
                    UIPasteboard.general.string = code
                    copied = true
                } label: {
                    Label(copied ? "Copiado" : "Copiar", systemImage: copied ? "checkmark" : "doc.on.doc")
                }
                .buttonStyle(.bordered)

                Toggle("Lo guardé en un lugar seguro", isOn: $saved)
                    .tint(Color.kalloq(scheme))

                Button("Continuar") { app.recoverySaved() }
                    .buttonStyle(.borderedProminent)
                    .tint(Color.kalloq(scheme))
                    .frame(maxWidth: .infinity)
                    .disabled(!saved)
            }
            .padding(24)
        }
    }
}
