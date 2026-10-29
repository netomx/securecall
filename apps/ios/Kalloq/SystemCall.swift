import AVFoundation
import CallKit
import Foundation
import WebRTC

@MainActor
protocol SystemCallListener: AnyObject {
    func onSystemAnswer()
    func onSystemDisconnect()
    func onSystemHold(_ held: Bool)
    func onSystemMute(_ muted: Bool)
    /// The system is not going to handle this call: carry it in our own screen instead.
    func onSystemUnavailable()
}

/**
 * The call as iOS sees it, through CallKit: the system shows the incoming-call screen, puts our
 * call on hold when a phone call arrives, and lets a car or a headset answer and hang up. It is the
 * counterpart of `call/SystemCall.kt` on Android.
 *
 * CallKit also owns the audio session. WebRTC is left in manual mode and the microphone only
 * starts in `provider(_:didActivate:)`; if the system never takes the call (it can refuse, and the
 * simulator has no telephony at all), `activateAudioDirectly` does it instead so the call still works.
 */
final class SystemCall: NSObject {
    private let provider: CXProvider
    private let controller = CXCallController()
    private weak var listener: SystemCallListener?
    private var callUUID: UUID?
    private var ownsAudioSession = false
    /// True once the system has actually acted on this call (answered, held, ended, started audio).
    private var systemTookTheCall = false

    override init() {
        let config = CXProviderConfiguration()
        config.supportsVideo = false
        config.maximumCallsPerCallGroup = 1
        config.maximumCallGroups = 1
        config.supportedHandleTypes = [.generic]
        provider = CXProvider(configuration: config)
        super.init()
        provider.setDelegate(self, queue: nil)

        // WebRTC must not touch the audio session on its own: CallKit decides when it starts.
        let session = RTCAudioSession.sharedInstance()
        session.useManualAudio = true
        session.isAudioEnabled = false
    }

    // ---------------------------------------------------------------- our side

    /// Someone is calling us: the system rings. Falls back to our own audio if it refuses.
    func reportIncoming(name: String, peerId: String, listener: SystemCallListener) {
        self.listener = listener
        systemTookTheCall = false
        let uuid = UUID()
        callUUID = uuid
        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: peerId)
        update.localizedCallerName = name
        update.hasVideo = false
        update.supportsGrouping = false
        update.supportsUngrouping = false
        update.supportsDTMF = false
        update.supportsHolding = true
        provider.reportNewIncomingCall(with: uuid, update: update) { [weak self] error in
            guard error != nil, let self else { return }
            self.callUUID = nil
            Task { @MainActor [listener = self.listener] in listener?.onSystemUnavailable() }
        }
    }

    /// We are calling someone: tell the system so it knows the phone is busy.
    func startOutgoing(name: String, peerId: String, listener: SystemCallListener) {
        self.listener = listener
        systemTookTheCall = false
        let uuid = UUID()
        callUUID = uuid
        let action = CXStartCallAction(call: uuid, handle: CXHandle(type: .generic, value: peerId))
        action.contactIdentifier = name
        controller.request(CXTransaction(action: action)) { [weak self] error in
            guard let self else { return }
            if error != nil {
                self.callUUID = nil
                Task { @MainActor in self.activateAudioDirectly() }
                return
            }
            let update = CXCallUpdate()
            update.localizedCallerName = name
            self.provider.reportCall(with: uuid, updated: update)
            self.provider.reportOutgoingCall(with: uuid, startedConnectingAt: nil)
        }
    }

    /// The other side picked up (outgoing call), or our audio is up.
    func reportConnected() {
        guard let callUUID else { return }
        provider.reportOutgoingCall(with: callUUID, connectedAt: nil)
    }

    /// The user answered in our own screen: go through CallKit so both paths are the same.
    func answer() {
        guard let callUUID else {
            activateAudioDirectly()
            Task { @MainActor [listener] in listener?.onSystemAnswer() }
            return
        }
        controller.request(CXTransaction(action: CXAnswerCallAction(call: callUUID))) { [weak self] error in
            guard error != nil, let self else { return }
            Task { @MainActor in
                self.activateAudioDirectly()
                self.listener?.onSystemAnswer()
            }
        }
    }

    /// The user hung up in our own screen.
    func end() {
        guard let callUUID else {
            finish()
            return
        }
        controller.request(CXTransaction(action: CXEndCallAction(call: callUUID))) { [weak self] _ in
            Task { @MainActor in self?.finish() }
        }
    }

    /// The call ended elsewhere (the other side hung up, the server timed it out...).
    func reportEnded(_ reason: CXCallEndedReason) {
        if let callUUID {
            provider.reportCall(with: callUUID, endedAt: nil, reason: reason)
        }
        finish()
    }

    func setMuted(_ muted: Bool) {
        guard let callUUID else { return }
        controller.request(CXTransaction(action: CXSetMutedCallAction(call: callUUID, muted: muted))) { _ in }
    }

    /// "Resume" after a phone call put ours on hold.
    func resume() {
        guard let callUUID else { return }
        let action = CXSetHeldCallAction(call: callUUID, onHold: false)
        controller.request(CXTransaction(action: action)) { _ in }
    }

    /// Earpiece or speaker. CallKit owns routing, but the speaker is still ours to override.
    func setSpeaker(_ on: Bool) {
        let session = RTCAudioSession.sharedInstance()
        session.lockForConfiguration()
        try? session.overrideOutputAudioPort(on ? .speaker : .none)
        session.unlockForConfiguration()
    }

    // ---------------------------------------------------------------- audio

    /// Only for when CallKit is not handling this call: start the session ourselves.
    private func activateAudioDirectly() {
        guard !ownsAudioSession else { return }
        ownsAudioSession = true
        let session = RTCAudioSession.sharedInstance()
        session.lockForConfiguration()
        do {
            try session.setConfiguration(RTCAudioSessionConfiguration.webRTC(), active: true)
            session.isAudioEnabled = true
        } catch {
            session.isAudioEnabled = false
        }
        session.unlockForConfiguration()
    }

    private func finish() {
        callUUID = nil
        listener = nil
        systemTookTheCall = false
        guard ownsAudioSession else { return }
        ownsAudioSession = false
        let session = RTCAudioSession.sharedInstance()
        session.lockForConfiguration()
        session.isAudioEnabled = false
        try? session.setActive(false)
        session.unlockForConfiguration()
    }
}

extension SystemCall: CXProviderDelegate {
    /**
     * The system cleared its calls. On a phone that means ours is gone and we must tear it down.
     * Where CallKit is not really there — the simulator has no telephony — the reset lands right
     * after we report the call, before the system has done anything with it; that is not the user
     * hanging up, and the call has to carry on in our own screen.
     */
    func providerDidReset(_ provider: CXProvider) {
        let tookIt = systemTookTheCall
        callUUID = nil
        systemTookTheCall = false
        Task { @MainActor [listener] in
            if tookIt { listener?.onSystemDisconnect() } else { listener?.onSystemUnavailable() }
        }
    }

    func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        systemTookTheCall = true
        Task { @MainActor [listener] in
            listener?.onSystemAnswer()
            action.fulfill()
        }
    }

    func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        systemTookTheCall = true
        Task { @MainActor [weak self] in
            self?.listener?.onSystemDisconnect()
            action.fulfill()
        }
    }

    func provider(_ provider: CXProvider, perform action: CXSetHeldCallAction) {
        systemTookTheCall = true
        Task { @MainActor [listener] in
            listener?.onSystemHold(action.isOnHold)
            action.fulfill()
        }
    }

    func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        systemTookTheCall = true
        Task { @MainActor [listener] in
            listener?.onSystemMute(action.isMuted)
            action.fulfill()
        }
    }

    func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        systemTookTheCall = true
        action.fulfill()
    }

    /// The one place the microphone is allowed to start.
    func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        systemTookTheCall = true
        let session = RTCAudioSession.sharedInstance()
        session.audioSessionDidActivate(audioSession)
        session.isAudioEnabled = true
    }

    func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        let session = RTCAudioSession.sharedInstance()
        session.audioSessionDidDeactivate(audioSession)
        session.isAudioEnabled = false
    }
}
