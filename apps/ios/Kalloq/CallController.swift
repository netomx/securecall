import AVFoundation
import Foundation
import KalloqCore
import WebRTC

enum CallPhase: Equatable { case calling, incoming, connecting, active, ended }

struct CallUi: Equatable, Identifiable {
    var id: String { callId }

    let callId: String
    let peerId: String
    let outgoing: Bool
    var phase: CallPhase
    var connectedAt: Date?
    var muted = false
    var speaker = false
    var endReason: String?
    var verified = false       // the contact was verified in person (QR)
    var code: String?          // comparison code from the handshake, the same on both phones
    var onHold = false         // the system put our call on hold (a phone call came in)
    var peerOnHold = false     // the other phone did
    var reconnecting = false   // the audio path is down; it ends if it does not come back
}

/// What the call needs to know about the account and its contacts.
@MainActor
protocol CallContext: AnyObject {
    func displayName(_ id: String) -> String
    func callKeys(for friendId: String) -> CallKeys?
    func show(_ notice: Notice)
}

/// The pinned identity key for a contact, with the QR PSK when they were verified in person.
struct CallKeys {
    let seed: Data
    let peerIk: Data
    let psk: Data?
}

/**
 * One call at a time, end to end. The SDP offer and answer travel inside the two Noise messages
 * (`docs/ARQUITECTURA.md` §4.4), so the DTLS fingerprints they carry are the real ones and the
 * server cannot listen or stand in the middle. ICE candidates then go through the transport keys
 * the handshake produced. Port of the call half of `AppController.kt`.
 */
@MainActor
final class CallController: ObservableObject {
    /// An offer older than this is not rung: the server could be replaying one it saw before.
    private static let offerMaxAge: TimeInterval = 10 * 60
    /// The server stops ringing at 45 s; give up on our own a little after that.
    private static let ringLimit: TimeInterval = 50
    /// How long a call waits with the audio path down before it is given up.
    private static let reconnectLimit: TimeInterval = 20
    /// After a push, how long to wait for the offer before the system stops ringing.
    private static let pushWaitLimit: TimeInterval = 20

    @Published private(set) var call: CallUi?

    private let server: ServerClient
    private let store: Store
    private weak var context: CallContext?

    private let engine = CallEngine()
    private let systemCall = SystemCall()

    private var handshake: NoiseHandshake?
    private var transport: Transport?
    private var pendingOffer: String?
    private var iceReady = false
    private var iceOut: [String] = []
    private var ringTimeout: Task<Void, Never>?
    private var reconnectTimeout: Task<Void, Never>?
    private var mediaRestart: Task<Void, Never>?
    private var mediaDown = false

    private var pushRegistry: PushRegistry?
    private var pushWait: Task<Void, Never>?
    // What the user did on the system's screen while it rang for a push, before the offer arrived.
    private var answerWhenOfferArrives = false
    private var declineWhenOfferArrives = false

    /// This phone's VoIP push token (hex), once iOS hands it over.
    private(set) var pushToken: String?
    var onPushToken: ((String?) -> Void)?
    /// A push said someone is calling: the session should reconnect now so the offer can arrive.
    var onPushArrived: (() -> Void)?

    init(server: ServerClient, store: Store, context: CallContext) {
        self.server = server
        self.store = store
        self.context = context
        pushRegistry = PushRegistry(systemCall: systemCall, listener: self,
                                    onToken: { [weak self] token in
                                        self?.pushToken = token
                                        self?.onPushToken?(token)
                                    },
                                    onPush: { [weak self] in self?.pushArrived() })
    }

    /**
     * The system is already ringing a nameless call (`SystemCall.reportPushedCall`). Reconnect so
     * the server can hand over the offer, and stop the ringing if it never comes: the caller gave
     * up, or this phone cannot get through.
     */
    private func pushArrived() {
        onPushArrived?()
        guard call == nil else { return }
        pushWait?.cancel()
        pushWait = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Self.pushWaitLimit * 1_000_000_000))
            guard let self, self.call == nil else { return }
            self.answerWhenOfferArrives = false
            self.declineWhenOfferArrives = false
            if self.systemCall.ringingForPush { self.systemCall.reportEnded(.remoteEnded) }
        }
    }

    /// An incoming call was refused before it rang in our screen: if a push made the system ring for
    /// it, that ringing has to stop too.
    private func stopPushedRinging() {
        answerWhenOfferArrives = false
        if call == nil, systemCall.ringingForPush { systemCall.reportEnded(.failed) }
    }

    // ---------------------------------------------------------------- outgoing

    func start(friendId: String) {
        guard call == nil else { return }
        guard let keys = context?.callKeys(for: friendId) else {
            context?.show(.error("key_changed"))
            return
        }
        let callId = UUID().uuidString.lowercased()
        call = CallUi(callId: callId, peerId: friendId, outgoing: true, phase: .calling,
                      verified: keys.psk != nil)
        systemCall.startOutgoing(name: context?.displayName(friendId) ?? friendId,
                                 peerId: friendId, listener: self)
        Task {
            guard await microphoneAllowed() else { return finish("no_microphone") }
            guard call?.callId == callId, call?.phase == .calling else { return }  // hung up while asking
            do {
                engine.open(iceServers: try await iceServers(), listener: self)
                let offer = try await engine.createOffer()
                let handshake = try CallCrypto.handshake(initiator: true, mySeed: keys.seed,
                                                         peerIk: keys.peerIk, psk: keys.psk, callId: callId)
                let payload = JSONValue.object([
                    "sdp": .string(offer),
                    "psk": .string(CallCrypto.pskId(keys.psk)),
                    // lets the other phone refuse an offer the server kept and replayed
                    "ts": .number(Date().timeIntervalSince1970 * 1000),
                ])
                self.handshake = handshake
                let message = try handshake.writeOffer(payload.encoded())
                try await server.call("call", [
                    "user_id": .string(friendId),
                    "call_id": .string(callId),
                    "msg": .string(Base64URL.encode(message)),
                ])
            } catch let error as ServerError {
                finish(error.code)
            } catch {
                finish("network")
            }
        }
    }

    // ---------------------------------------------------------------- incoming

    func onIncoming(_ event: JSONValue) {
        guard let callId = event.string("call_id"), let from = event.string("user_id") else { return }
        if call?.callId == callId, call?.phase == .incoming { return }  // the same call again after reconnecting
        if call != nil {
            // Already in a call; the server normally answers "busy" before this.
            reject(callId)
            return
        }
        let refuse = { [self] (notice: String?) in
            reject(callId)
            stopPushedRinging()
            if let notice { context?.show(.error(notice)) }
        }
        if declineWhenOfferArrives {
            // Declined on the system's screen while it rang for the push, before we knew who it was.
            declineWhenOfferArrives = false
            return refuse(nil)
        }
        // Only a call made with the identity key we pinned for this contact decrypts.
        guard let keys = context?.callKeys(for: from) else { return refuse("key_changed") }
        guard let raw = event.string("msg").flatMap(Base64URL.decode),
              let handshake = try? CallCrypto.handshake(initiator: false, mySeed: keys.seed,
                                                        peerIk: keys.peerIk, psk: keys.psk, callId: callId),
              let opened = try? handshake.readOffer(raw),
              let offer = JSONValue.parse(String(decoding: opened, as: UTF8.self)),
              let sdp = offer.string("sdp") else { return refuse("e2e_failed") }
        guard offer.string("psk") == CallCrypto.pskId(keys.psk) else { return refuse("verify_mismatch") }
        // The server could replay a call it relayed before: it decrypts, but must not ring again.
        let sentAt = offer["ts"]?.doubleValue.map { $0 / 1000 }
        guard let sentAt, abs(Date().timeIntervalSince1970 - sentAt) <= Self.offerMaxAge,
              !store.callSeen(callId) else { return refuse(nil) }

        pendingOffer = sdp
        self.handshake = handshake
        pushWait?.cancel()
        call = CallUi(callId: callId, peerId: from, outgoing: false, phase: .incoming,
                      verified: keys.psk != nil)
        systemCall.reportIncoming(name: context?.displayName(from) ?? from, peerId: from, listener: self)
        ringTimeout = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Self.ringLimit * 1_000_000_000))
            guard let self, self.call?.callId == callId, self.call?.phase == .incoming else { return }
            self.reject(callId)
            self.finish("timeout")
        }
        if answerWhenOfferArrives {
            // Answered on the system's screen before the offer was here: pick up now.
            answerWhenOfferArrives = false
            accept()
        }
    }

    /// The user tapped answer in our own screen. It goes through CallKit all the same, because the
    /// system is what starts the audio session; `SystemCall` falls back if it is not handling this call.
    func answerFromUI() { systemCall.answer() }

    /// The user answered, here or from the system's call screen.
    func accept() {
        guard let current = call, current.phase == .incoming, let sdp = pendingOffer else { return }
        pendingOffer = nil
        ringTimeout?.cancel()
        call?.phase = .connecting
        Task {
            guard await microphoneAllowed() else {
                reject(current.callId)
                return finish("no_microphone")
            }
            do {
                engine.open(iceServers: try await iceServers(), listener: self)
                let answer = try await engine.acceptOffer(sdp)
                guard let handshake else { throw CallError.noConnection }
                let payload = JSONValue.object(["sdp": .string(answer)])
                let (message, transport) = try handshake.writeAnswer(payload.encoded())
                self.transport = transport
                if call?.callId == current.callId {
                    call?.code = CallCrypto.callCode(handshakeHash: handshake.handshakeHash)
                }
                try await server.call("call_answer", [
                    "call_id": .string(current.callId),
                    "msg": .string(Base64URL.encode(message)),
                ])
                iceReady = true
                flushIce()
            } catch let error as ServerError {
                finish(error.code)
            } catch {
                try? await server.call("call_end", ["call_id": .string(current.callId)])
                finish("failed")
            }
        }
    }

    func rejectCurrent() {
        guard let current = call, current.phase == .incoming else { return }
        reject(current.callId)
        finish("rejected")
        systemCall.end()
    }

    func hangUp() {
        guard let current = call, current.phase != .ended else { return }
        if current.phase == .incoming { return rejectCurrent() }
        let callId = current.callId
        Task { try? await server.call("call_end", ["call_id": .string(callId)]) }
        systemCall.end()
        finish("hangup")
    }

    // ---------------------------------------------------------------- events from the server

    func onAnswered(_ event: JSONValue) {
        guard let current = call, current.outgoing, event.string("call_id") == current.callId,
              let handshake else { return }
        // Fails unless the answer was made by the contact we pinned and the QR PSKs match.
        guard let raw = event.string("msg").flatMap(Base64URL.decode),
              let opened = try? handshake.readAnswer(raw),
              let payload = JSONValue.parse(String(decoding: opened.0, as: UTF8.self)),
              let sdp = payload.string("sdp") else {
            Task { try? await server.call("call_end", ["call_id": .string(current.callId)]) }
            finish("e2e_failed")
            return
        }
        transport = opened.1
        iceReady = true
        call?.phase = .connecting
        call?.code = CallCrypto.callCode(handshakeHash: handshake.handshakeHash)
        systemCall.reportConnected()
        flushIce()
        Task {
            do { try await engine.setAnswer(sdp) } catch { onFailed() }
        }
    }

    func onRemoteIce(_ event: JSONValue) {
        guard let current = call, event.string("call_id") == current.callId, let transport else { return }
        guard let raw = event.string("msg").flatMap(Base64URL.decode),
              let opened = try? transport.decrypt(raw),
              let message = JSONValue.parse(String(decoding: opened, as: UTF8.self)) else { return }

        switch message.string("restart") {
        case "offer" where !current.outgoing:
            if let sdp = message.string("sdp") {
                Task {
                    guard let answer = try? await engine.acceptOffer(sdp) else { return }
                    await sendSecure(current.callId, .object(["restart": .string("answer"), "sdp": .string(answer)]))
                }
            }
        case "answer" where current.outgoing:
            if let sdp = message.string("sdp") {
                Task { try? await engine.setAnswer(sdp) }
            }
        default:
            break
        }

        if let held = message["hold"]?.boolValue { call?.peerOnHold = held }

        guard let candidate = message.string("candidate") else { return }
        engine.addRemoteCandidate(RTCIceCandidate(sdp: candidate,
                                                  sdpMLineIndex: Int32(message["sdpMLineIndex"]?.intValue ?? 0),
                                                  sdpMid: message.string("sdpMid")))
    }

    func onEnded(_ event: JSONValue) {
        guard event.string("call_id") == call?.callId else { return }
        systemCall.reportEnded(event.string("reason") == "rejected" ? .unanswered : .remoteEnded)
        finish(event.string("reason"))
    }

    func onReconnecting(_ event: JSONValue) {
        guard event.string("call_id") == call?.callId else { return }
        call?.reconnecting = true
        startReconnectLimit()
    }

    func onResumed(_ event: JSONValue) {
        guard event.string("call_id") == call?.callId else { return }
        call?.reconnecting = mediaDown
        if !mediaDown { reconnectTimeout?.cancel() }
        // One of us is probably on a new network: rebuild the audio path (only the caller offers).
        if call?.outgoing == true { restartMedia() }
    }

    /// The session dropped for good (signed out, session revoked).
    func sessionLost() {
        guard call != nil else { return }
        systemCall.reportEnded(.failed)
        finish("disconnected")
    }

    // ---------------------------------------------------------------- controls

    func toggleMute() {
        guard let current = call else { return }
        let muted = !current.muted
        call?.muted = muted
        engine.setOnHold(current.onHold, muted: muted)
        systemCall.setMuted(muted)
    }

    func toggleSpeaker() {
        guard let current = call else { return }
        let speaker = !current.speaker
        call?.speaker = speaker
        systemCall.setSpeaker(speaker)
    }

    /// "Resume" on the call screen after a phone call put ours on hold.
    func resume() {
        guard call?.onHold == true else { return }
        systemCall.resume()
    }

    // ---------------------------------------------------------------- plumbing

    private func iceServers() async throws -> [RTCIceServer] {
        let reply = try await server.call("turn")
        let urls = reply.strings("urls")
        // A test server can hand back no relay at all; on one machine host candidates are enough.
        guard !urls.isEmpty else { return [] }
        return [RTCIceServer(urlStrings: urls,
                             username: reply.string("username"),
                             credential: reply.string("credential"))]
    }

    private func reject(_ callId: String) {
        Task { try? await server.call("call_reject", ["call_id": .string(callId)]) }
    }

    private func sendSecure(_ callId: String, _ message: JSONValue) async {
        guard let transport, let encrypted = try? transport.encrypt(message.encoded()) else { return }
        try? await server.call("call_ice", [
            "call_id": .string(callId),
            "msg": .string(Base64URL.encode(encrypted)),
        ])
    }

    /// Sends our ICE candidates, encrypted, once the handshake allows it.
    private func flushIce() {
        guard let current = call, iceReady, transport != nil else { return }
        let pending = iceOut
        iceOut.removeAll()
        Task {
            for candidate in pending {
                guard let value = JSONValue.parse(candidate) else { continue }
                await sendSecure(current.callId, value)
            }
        }
    }

    /// Caller only: ICE restart, the new offer sent over the call's encrypted channel.
    private func restartMedia() {
        guard let current = call, current.outgoing else { return }
        mediaRestart?.cancel()
        mediaRestart = Task { [weak self] in
            guard let self, let sdp = try? await self.engine.restartOffer() else { return }
            await self.sendSecure(current.callId, .object(["restart": .string("offer"), "sdp": .string(sdp)]))
        }
    }

    private func startReconnectLimit() {
        guard reconnectTimeout == nil, let current = call else { return }
        reconnectTimeout = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Self.reconnectLimit * 1_000_000_000))
            guard let self, self.call?.callId == current.callId, self.call?.reconnecting == true else { return }
            self.reconnectTimeout = nil
            Task { try? await self.server.call("call_end", ["call_id": .string(current.callId)]) }
            self.systemCall.reportEnded(.failed)
            self.finish("disconnected")
        }
    }

    private func microphoneAllowed() async -> Bool {
        await withCheckedContinuation { continuation in
            AVAudioSession.sharedInstance().requestRecordPermission { continuation.resume(returning: $0) }
        }
    }

    /// Tears down the media and leaves the reason on screen for a moment.
    private func finish(_ reason: String?) {
        guard let current = call, current.phase != .ended else { return }
        if !current.outgoing { store.markCallSeen(current.callId) }
        ringTimeout?.cancel()
        reconnectTimeout?.cancel()
        mediaRestart?.cancel()
        ringTimeout = nil
        reconnectTimeout = nil
        mediaDown = false
        handshake = nil
        transport = nil
        pendingOffer = nil
        iceReady = false
        iceOut.removeAll()
        engine.close()
        call?.phase = .ended
        call?.endReason = reason
        let callId = current.callId
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            if self?.call?.callId == callId, self?.call?.phase == .ended { self?.call = nil }
        }
    }
}

extension CallController: CallEngineListener {
    func onIceCandidate(_ candidate: RTCIceCandidate) {
        guard call != nil else { return }
        let message = JSONValue.object([
            "candidate": .string(candidate.sdp),
            "sdpMid": candidate.sdpMid.map { JSONValue.string($0) } ?? .null,
            "sdpMLineIndex": .number(Double(candidate.sdpMLineIndex)),
        ])
        iceOut.append(String(decoding: message.encoded(), as: UTF8.self))
        flushIce()
    }

    func onConnected() {
        guard call != nil else { return }
        mediaDown = false
        call?.reconnecting = false
        reconnectTimeout?.cancel()
        reconnectTimeout = nil
        if call?.phase != .active {
            call?.phase = .active
            call?.connectedAt = Date()
            systemCall.reportConnected()
        }
    }

    func onDisconnected() {
        guard call?.phase == .active else { return }
        mediaDown = true
        call?.reconnecting = true
        startReconnectLimit()
        // WebRTC often recovers by itself; if not, the caller rebuilds the path.
        if call?.outgoing == true {
            mediaRestart?.cancel()
            mediaRestart = Task { [weak self] in
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                guard let self, self.mediaDown else { return }
                self.restartMedia()
            }
        }
    }

    func onFailed() {
        guard let current = call else { return }
        if current.phase == .active {
            mediaDown = true
            call?.reconnecting = true
            startReconnectLimit()
            if current.outgoing { restartMedia() }
            return
        }
        Task { try? await server.call("call_end", ["call_id": .string(current.callId)]) }
        systemCall.reportEnded(.failed)
        finish("failed")
    }
}

extension CallController: SystemCallListener {
    func onSystemAnswer() {
        if call == nil, systemCall.ringingForPush {
            answerWhenOfferArrives = true  // the push rang and the user picked up before the offer arrived
            return
        }
        accept()
    }

    func onSystemDisconnect() {
        guard let current = call, current.phase != .ended else {
            if systemCall.ringingForPush {
                // Declined on the system's screen before the offer arrived: refuse it when it does.
                declineWhenOfferArrives = true
                answerWhenOfferArrives = false
            }
            return
        }
        let callId = current.callId
        if current.phase == .incoming {
            reject(callId)
            finish(nil)
        } else {
            Task { try? await server.call("call_end", ["call_id": .string(callId)]) }
            finish("hangup")
        }
    }

    /// A phone call took over (or ended): silence both directions and tell the other phone.
    func onSystemHold(_ held: Bool) {
        guard let current = call, current.phase != .incoming, current.phase != .ended,
              current.onHold != held else { return }
        call?.onHold = held
        engine.setOnHold(held, muted: current.muted)
        Task { await sendSecure(current.callId, .object(["hold": .bool(held)])) }
    }

    func onSystemMute(_ muted: Bool) {
        guard let current = call, current.muted != muted else { return }
        call?.muted = muted
        engine.setOnHold(current.onHold, muted: muted)
    }

    /**
     * The system will not take this call. Nothing to do: the call lives in our own screen, which is
     * already showing, and `SystemCall` starts the audio session itself once the user answers. It
     * happens in the simulator, which has no telephony at all, and would happen on a phone where
     * CallKit refuses the call.
     */
    func onSystemUnavailable() {}
}
