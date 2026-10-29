import Foundation
import PushKit

/**
 * VoIP pushes through PushKit: how a call reaches an iPhone whose app is closed or suspended. The
 * counterpart of `push/Push.kt` (FCM) on Android.
 *
 * The delegate runs on the main queue but outside Swift's main actor, and that matters: the call
 * has to be reported to CallKit before `didReceiveIncomingPushWith` returns, so that one step goes
 * straight to `SystemCall`; everything else hops onto the main actor afterwards.
 */
final class PushRegistry: NSObject {
    private let registry = PKPushRegistry(queue: .main)
    private let systemCall: SystemCall
    private weak var listener: SystemCallListener?
    private let onToken: @MainActor (String?) -> Void
    private let onPush: @MainActor () -> Void

    init(systemCall: SystemCall, listener: SystemCallListener,
         onToken: @escaping @MainActor (String?) -> Void, onPush: @escaping @MainActor () -> Void) {
        self.systemCall = systemCall
        self.listener = listener
        self.onToken = onToken
        self.onPush = onPush
        super.init()
        registry.delegate = self
        registry.desiredPushTypes = [.voIP]
    }
}

extension PushRegistry: PKPushRegistryDelegate {
    func pushRegistry(_ registry: PKPushRegistry, didUpdate credentials: PKPushCredentials, for type: PKPushType) {
        guard type == .voIP else { return }
        let token = credentials.token.map { String(format: "%02x", $0) }.joined()
        Task { @MainActor [onToken] in onToken(token) }
    }

    func pushRegistry(_ registry: PKPushRegistry, didInvalidatePushTokenFor type: PKPushType) {
        guard type == .voIP else { return }
        Task { @MainActor [onToken] in onToken(nil) }
    }

    func pushRegistry(_ registry: PKPushRegistry, didReceiveIncomingPushWith payload: PKPushPayload,
                      for type: PKPushType, completion: @escaping () -> Void) {
        systemCall.reportPushedCall(listener: listener, completion: completion)
        Task { @MainActor [onPush] in onPush() }
    }
}
