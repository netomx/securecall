import SwiftUI

@main
struct KalloqApp: App {
    @StateObject private var app = AppController()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(app)
        }
        .onChange(of: scenePhase) { phase in
            // Coming back to the front is the best moment to reconnect: iOS suspends the socket
            // while the app is in the background.
            if phase == .active { app.reconnectNow() }
        }
    }
}
