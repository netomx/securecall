import CryptoKit
import Foundation

/**
 * Noise_KKpsk2_25519_AESGCM_SHA256 (https://noiseprotocol.org/noise.html), the per-call handshake:
 *
 *   KKpsk2:
 *     -> s
 *     <- s
 *     ...
 *     -> e, es, ss          (call offer: carries the SDP offer, encrypted)
 *     <- e, ee, se, psk     (call answer: carries the SDP answer, encrypted)
 *
 * Both static keys are the X25519 forms of the identity keys, known in advance (pinned).
 * Every call uses fresh ephemeral keys, so the transport keys have forward secrecy.
 *
 * Port of `Noise.kt` on Android; the two must agree byte for byte.
 */
public final class NoiseHandshake {
    public static let protocolName = "Noise_KKpsk2_25519_AESGCM_SHA256"
    public static let tag = 16

    private let initiator: Bool
    private let s: Data   // our static X25519 private key
    private let rs: Data  // their static X25519 public key
    private let psk: Data
    private let ephemeral: Data
    private var ck: Data
    private var h: Data
    private let cipher = CipherState()
    private var re: Data?
    private var step = 0

    /// `ephemeral` is fixed only in tests; otherwise a fresh key per call.
    public init(initiator: Bool, s: Data, rs: Data, psk: Data, prologue: Data, ephemeral: Data? = nil) throws {
        guard psk.count == 32 else { throw CryptoError.badKey }
        self.initiator = initiator
        self.s = s
        self.rs = rs
        self.psk = psk
        self.ephemeral = ephemeral ?? Keys.newPrivate()
        h = Data(Self.protocolName.utf8)
        ck = h
        mixHash(prologue)
        let sPub = try Keys.publicOf(s)
        // Pre-messages: initiator's static first, then responder's.
        if initiator {
            mixHash(sPub); mixHash(rs)
        } else {
            mixHash(rs); mixHash(sPub)
        }
    }

    /// Handshake hash once finished: identical on both sides, used for the call's comparison code.
    public var handshakeHash: Data { h }

    /// Message 1 (initiator).
    public func writeOffer(_ payload: Data) throws -> Data {
        guard initiator, step == 0 else { throw CryptoError.badState }
        step = 1
        let ePub = try Keys.publicOf(ephemeral)
        mixHash(ePub); mixKey(ePub)
        mixKey(try Keys.dh(ephemeral, rs))  // es
        mixKey(try Keys.dh(s, rs))          // ss
        return ePub + (try encryptAndHash(payload))
    }

    /// Message 1 (responder). Throws unless it was made by the pinned peer for this prologue.
    public func readOffer(_ message: Data) throws -> Data {
        guard !initiator, step == 0 else { throw CryptoError.badState }
        step = 1
        guard message.count >= 32 + Self.tag else { throw CryptoError.shortMessage }
        let e = Data(message.prefix(32))
        re = e
        mixHash(e); mixKey(e)
        mixKey(try Keys.dh(s, e))   // es
        mixKey(try Keys.dh(s, rs))  // ss
        return try decryptAndHash(Data(message.dropFirst(32)))
    }

    /// Message 2 (responder). Returns the message and the transport keys.
    public func writeAnswer(_ payload: Data) throws -> (Data, Transport) {
        guard !initiator, step == 1, let re else { throw CryptoError.badState }
        step = 2
        let ePub = try Keys.publicOf(ephemeral)
        mixHash(ePub); mixKey(ePub)
        mixKey(try Keys.dh(ephemeral, re))  // ee
        mixKey(try Keys.dh(ephemeral, rs))  // se
        mixKeyAndHash(psk)
        return (ePub + (try encryptAndHash(payload)), split())
    }

    /// Message 2 (initiator). Throws if the peer is not who we pinned, or the PSKs differ.
    public func readAnswer(_ message: Data) throws -> (Data, Transport) {
        guard initiator, step == 1 else { throw CryptoError.badState }
        step = 2
        guard message.count >= 32 + Self.tag else { throw CryptoError.shortMessage }
        let e = Data(message.prefix(32))
        mixHash(e); mixKey(e)
        mixKey(try Keys.dh(ephemeral, e))  // ee
        mixKey(try Keys.dh(s, e))          // se
        mixKeyAndHash(psk)
        let payload = try decryptAndHash(Data(message.dropFirst(32)))
        return (payload, split())
    }

    private func mixHash(_ data: Data) {
        h = Keys.sha256(h, data)
    }

    private func mixKey(_ ikm: Data) {
        let out = Self.noiseHkdf(ck, ikm, 2)
        ck = out[0]
        cipher.start(out[1])
    }

    private func mixKeyAndHash(_ ikm: Data) {
        let out = Self.noiseHkdf(ck, ikm, 3)
        ck = out[0]
        mixHash(out[1])
        cipher.start(out[2])
    }

    private func encryptAndHash(_ plain: Data) throws -> Data {
        let ct = try cipher.encrypt(ad: h, plain)
        mixHash(ct)
        return ct
    }

    private func decryptAndHash(_ ct: Data) throws -> Data {
        let plain = try cipher.decrypt(ad: h, ct)
        mixHash(ct)
        return plain
    }

    private func split() -> Transport {
        let keys = Self.noiseHkdf(ck, Data(), 2)
        let (send, recv) = initiator ? (keys[0], keys[1]) : (keys[1], keys[0])
        let sendState = CipherState()
        sendState.start(send)
        let recvState = CipherState()
        recvState.start(recv)
        return Transport(send: sendState, recv: recvState)
    }

    private static func noiseHkdf(_ ck: Data, _ ikm: Data, _ n: Int) -> [Data] {
        let temp = Keys.hmac(ck, ikm)
        var out: [Data] = []
        var prev = Data()
        for i in 1...n {
            prev = Keys.hmac(temp, prev, Data([UInt8(i)]))
            out.append(prev)
        }
        return out
    }
}

/**
 * Transport messages after the handshake (ICE candidates). Each carries its 8-byte nonce in clear so
 * that a lost or reordered message does not break the rest; a nonce must be higher than the last one.
 */
public final class Transport {
    private let send: CipherState
    private let recv: CipherState
    private var lastReceived: Int64 = -1

    init(send: CipherState, recv: CipherState) {
        self.send = send
        self.recv = recv
    }

    public func encrypt(_ plain: Data) throws -> Data {
        let n = send.nonce
        return Self.bigEndian(n) + (try send.encrypt(ad: Data(), plain))
    }

    public func decrypt(_ message: Data) throws -> Data {
        guard message.count >= 8 + NoiseHandshake.tag else { throw CryptoError.shortMessage }
        let bytes = [UInt8](message)
        var n: UInt64 = 0
        for i in 0..<8 { n = (n << 8) | UInt64(bytes[i]) }
        let signed = Int64(bitPattern: n)
        guard signed > lastReceived else { throw CryptoError.replay }
        let plain = try recv.decrypt(at: n, ad: Data(), Data(bytes[8...]))
        lastReceived = signed
        return plain
    }

    static func bigEndian(_ n: UInt64) -> Data { withUnsafeBytes(of: n.bigEndian) { Data($0) } }
}

final class CipherState {
    private var k: SymmetricKey?
    private(set) var nonce: UInt64 = 0

    func start(_ key: Data) {
        k = SymmetricKey(data: key.prefix(32))
        nonce = 0
    }

    func encrypt(ad: Data, _ plain: Data) throws -> Data {
        guard let k else { return plain }
        let out = try Self.seal(k, nonce, ad, plain)
        nonce += 1
        return out
    }

    func decrypt(ad: Data, _ ct: Data) throws -> Data {
        guard let k else { return ct }
        let out = try Self.open(k, nonce, ad, ct)
        nonce += 1
        return out
    }

    func decrypt(at n: UInt64, ad: Data, _ ct: Data) throws -> Data {
        guard let k else { throw CryptoError.badState }
        return try Self.open(k, n, ad, ct)
    }

    /// Noise AESGCM nonce: four zero bytes then the counter, big-endian.
    private static func iv(_ n: UInt64) -> Data { Data(count: 4) + Transport.bigEndian(n) }

    private static func seal(_ k: SymmetricKey, _ n: UInt64, _ ad: Data, _ plain: Data) throws -> Data {
        let box = try AES.GCM.seal(plain, using: k, nonce: AES.GCM.Nonce(data: iv(n)), authenticating: ad)
        return box.ciphertext + box.tag
    }

    private static func open(_ k: SymmetricKey, _ n: UInt64, _ ad: Data, _ ct: Data) throws -> Data {
        guard ct.count >= NoiseHandshake.tag else { throw CryptoError.shortMessage }
        let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: iv(n)),
                                        ciphertext: ct.dropLast(NoiseHandshake.tag),
                                        tag: ct.suffix(NoiseHandshake.tag))
        return try AES.GCM.open(box, using: k, authenticating: ad)
    }
}
