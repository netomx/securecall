import Foundation
import WebRTC

@MainActor
protocol CallEngineListener: AnyObject {
    func onIceCandidate(_ candidate: RTCIceCandidate)
    func onConnected()
    func onDisconnected()  // audio interrupted; WebRTC may still recover on its own
    func onFailed()
}

/**
 * One audio-only WebRTC peer connection at a time (DTLS-SRTP, Opus). Port of `call/CallEngine.kt`.
 *
 * The audio session is in manual mode: only CallKit starts and stops it, in
 * `provider(_:didActivate:)`, which is what iOS requires of a calling app.
 */
final class CallEngine: NSObject {
    private static let factory: RTCPeerConnectionFactory = {
        RTCInitializeSSL()
        return RTCPeerConnectionFactory(encoderFactory: RTCDefaultVideoEncoderFactory(),
                                        decoderFactory: RTCDefaultVideoDecoderFactory())
    }()

    private static let audioOnly = RTCMediaConstraints(
        mandatoryConstraints: ["OfferToReceiveAudio": "true", "OfferToReceiveVideo": "false"],
        optionalConstraints: nil)

    private weak var listener: CallEngineListener?
    private var connection: RTCPeerConnection?
    private var track: RTCAudioTrack?
    private var pendingRemote: [RTCIceCandidate] = []
    private var remoteDescriptionSet = false

    func open(iceServers: [RTCIceServer], listener: CallEngineListener) {
        close()
        self.listener = listener

        let config = RTCConfiguration()
        config.iceServers = iceServers
        config.sdpSemantics = .unifiedPlan
        config.bundlePolicy = .maxBundle
        config.rtcpMuxPolicy = .require
        config.continualGatheringPolicy = .gatherContinually

        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        guard let connection = Self.factory.peerConnection(with: config, constraints: constraints, delegate: self) else {
            return
        }
        let source = Self.factory.audioSource(with: Self.audioOnly)
        let track = Self.factory.audioTrack(with: source, trackId: "audio0")
        connection.add(track, streamIds: ["stream0"])
        self.connection = connection
        self.track = track
    }

    func createOffer() async throws -> String {
        let connection = try connectionOrFail()
        let offer = try await connection.offer(for: Self.audioOnly)
        try await connection.setLocalDescription(offer)
        return offer.sdp
    }

    func acceptOffer(_ sdp: String) async throws -> String {
        let connection = try connectionOrFail()
        try await connection.setRemoteDescription(RTCSessionDescription(type: .offer, sdp: sdp))
        remoteSet()
        let answer = try await connection.answer(for: Self.audioOnly)
        try await connection.setLocalDescription(answer)
        return answer.sdp
    }

    func setAnswer(_ sdp: String) async throws {
        let connection = try connectionOrFail()
        try await connection.setRemoteDescription(RTCSessionDescription(type: .answer, sdp: sdp))
        remoteSet()
    }

    /// New offer that gathers fresh network paths (after a network change).
    func restartOffer() async throws -> String {
        try connectionOrFail().restartIce()
        return try await createOffer()
    }

    /// Candidates can arrive before the remote description: queue them until then.
    func addRemoteCandidate(_ candidate: RTCIceCandidate) {
        guard let connection else { return }
        if remoteDescriptionSet {
            connection.add(candidate) { _ in }
        } else {
            pendingRemote.append(candidate)
        }
    }

    /// On hold: no microphone and no sound from the other side. `muted` is the user's own mute.
    func setOnHold(_ held: Bool, muted: Bool) {
        track?.isEnabled = !held && !muted
        connection?.receivers.forEach { ($0.track as? RTCAudioTrack)?.isEnabled = !held }
    }

    func close() {
        connection?.close()
        connection = nil
        track = nil
        listener = nil
        pendingRemote.removeAll()
        remoteDescriptionSet = false
    }

    private func connectionOrFail() throws -> RTCPeerConnection {
        guard let connection else { throw CallError.noConnection }
        return connection
    }

    private func remoteSet() {
        remoteDescriptionSet = true
        pendingRemote.forEach { candidate in connection?.add(candidate) { _ in } }
        pendingRemote.removeAll()
    }
}

enum CallError: Error { case noConnection }

extension CallEngine: RTCPeerConnectionDelegate {
    func peerConnection(_ connection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        Task { @MainActor [listener] in listener?.onIceCandidate(candidate) }
    }

    func peerConnection(_ connection: RTCPeerConnection, didChange state: RTCPeerConnectionState) {
        Task { @MainActor [listener] in
            switch state {
            case .connected: listener?.onConnected()
            case .disconnected: listener?.onDisconnected()
            case .failed: listener?.onFailed()
            default: break
            }
        }
    }

    // The rest of the delegate is required by the protocol but says nothing we act on.
    func peerConnection(_ connection: RTCPeerConnection, didChange state: RTCSignalingState) {}
    func peerConnection(_ connection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    func peerConnection(_ connection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    func peerConnectionShouldNegotiate(_ connection: RTCPeerConnection) {}
    func peerConnection(_ connection: RTCPeerConnection, didChange state: RTCIceConnectionState) {}
    func peerConnection(_ connection: RTCPeerConnection, didChange state: RTCIceGatheringState) {}
    func peerConnection(_ connection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    func peerConnection(_ connection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
}

// WebRTC's completion-handler API, as async calls.
private extension RTCPeerConnection {
    func offer(for constraints: RTCMediaConstraints) async throws -> RTCSessionDescription {
        try await withCheckedThrowingContinuation { continuation in
            offer(for: constraints) { sdp, error in
                if let sdp { continuation.resume(returning: sdp) }
                else { continuation.resume(throwing: error ?? CallError.noConnection) }
            }
        }
    }

    func answer(for constraints: RTCMediaConstraints) async throws -> RTCSessionDescription {
        try await withCheckedThrowingContinuation { continuation in
            answer(for: constraints) { sdp, error in
                if let sdp { continuation.resume(returning: sdp) }
                else { continuation.resume(throwing: error ?? CallError.noConnection) }
            }
        }
    }

    func setLocalDescription(_ sdp: RTCSessionDescription) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            setLocalDescription(sdp) { error in
                if let error { continuation.resume(throwing: error) } else { continuation.resume() }
            }
        }
    }

    func setRemoteDescription(_ sdp: RTCSessionDescription) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            setRemoteDescription(sdp) { error in
                if let error { continuation.resume(throwing: error) } else { continuation.resume() }
            }
        }
    }
}
