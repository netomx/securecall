import SwiftUI

/// Root of the app: which screen to show depends on whether there is a session.
struct ContentView: View {
    @EnvironmentObject private var app: AppController

    var body: some View {
        Group {
            switch app.phase {
            case .loading:
                ProgressView()
            case .loggedOut:
                AuthView()
            case .showRecovery(let code):
                RecoveryCodeView(code: code)
            case .loggedIn:
                ContactsView()
            }
        }
        .animation(.default, value: app.phase)
        .noticeAlert()
        // Universal Links (https://call.l8.nz/a/<id>) and kalloq://add/<id>.
        .onOpenURL { url in app.openInvite(url.absoluteString) }
        .confirmationDialog("¿Agregar contacto?", isPresented: Binding(
            get: { app.pendingInvite != nil && app.phase == .loggedIn },
            set: { if !$0 { app.closeInvite() } }
        ), titleVisibility: .visible) {
            Button("Enviar solicitud") {
                if let id = app.pendingInvite { app.addFriend(id) }
                app.closeInvite()
            }
            Button("Cancelar", role: .cancel) { app.closeInvite() }
        } message: {
            Text("¿Enviar solicitud de contacto a \(app.pendingInvite ?? "")?")
        }
        .fullScreenCover(item: Binding(get: { app.call }, set: { _ in })) { call in
            CallView(call: call)
        }
    }
}

/// Server errors and the short messages the screens raise, as one alert.
private struct NoticeAlert: ViewModifier {
    @EnvironmentObject private var app: AppController

    func body(content: Content) -> some View {
        content.alert(item: $app.notice) { notice in
            Alert(title: Text(notice.isError ? "No se pudo" : "Kalloq"),
                  message: Text(notice.text),
                  dismissButton: .default(Text("Entendido")))
        }
    }
}

extension View {
    func noticeAlert() -> some View { modifier(NoticeAlert()) }
}

/// The brand mark until the logo becomes an asset.
struct BrandMark: View {
    @Environment(\.colorScheme) private var scheme
    var size: CGFloat = 44

    var body: some View {
        Image(systemName: "lock.shield.fill")
            .font(.system(size: size))
            .foregroundStyle(Color.kalloq(scheme))
    }
}
