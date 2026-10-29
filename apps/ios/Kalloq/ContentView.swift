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
