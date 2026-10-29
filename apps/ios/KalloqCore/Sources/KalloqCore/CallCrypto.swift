import Foundation

/**
 * The end-to-end layer of a call. The SDP offer and answer travel inside the two Noise handshake
 * messages, so they are encrypted and authenticated by both identity keys. The DTLS fingerprints
 * in the SDP are therefore the real ones, and WebRTC's DTLS-SRTP keys are shared only by the two
 * phones: neither the server nor TURN can decrypt or swap the audio.
 */
public enum CallCrypto {
    /// PSK for contacts not verified in person (security then rests on the pinned identity keys).
    public static let noPSK = Data(count: 32)

    public static func prologue(callId: String) -> Data { Data("kalloq/call/1\0\(callId)".utf8) }

    public static func handshake(initiator: Bool, mySeed: Data, peerIk: Data, psk: Data?,
                                 callId: String) throws -> NoiseHandshake {
        try NoiseHandshake(initiator: initiator,
                           s: Keys.x25519Private(edSeed: mySeed),
                           rs: Keys.x25519Public(edPublic: peerIk),
                           psk: psk ?? noPSK,
                           prologue: prologue(callId: callId))
    }

    /// Short ID of the PSK in use, sent inside the encrypted offer so a mismatch is reported clearly.
    public static func pskId(_ psk: Data?) -> String {
        guard let psk else { return "none" }
        return Keys.sha256(Data("kalloq/psk-id/1".utf8), psk).prefix(8).hex
    }

    /// Code both people see during the call; if it matches, nobody is in the middle.
    public static func callCode(handshakeHash: Data) -> String {
        digits(Keys.sha256(Data("kalloq/call-code/1".utf8), handshakeHash), 6)
    }
}

/// `count` decimal digits from a hash, grouped in threes.
func digits(_ hash: Data, _ count: Int) -> String {
    var v: UInt64 = 0
    for b in hash.prefix(8) { v = (v << 8) | UInt64(b) }
    var out = ""
    for i in 0..<count {
        if i > 0, i % 3 == 0 { out += " " }
        out += String(v % 10)
        v /= 10
    }
    return out
}
