import CryptoKit
import Foundation

public enum CryptoError: Error {
    case badKey
    case shortMessage
    case replay
    case badState
}

/**
 * Key conversions and primitives shared by the call handshake and QR verification. Port of
 * `Keys.kt` on Android: every value here has to come out byte for byte the same, and the tests
 * check it against the vectors of `tools/kalloq_crypto.py`.
 */
public enum Keys {
    /// X25519 private scalar for an Ed25519 seed (same as libsodium's crypto_sign_ed25519_sk_to_curve25519).
    public static func x25519Private(edSeed: Data) -> Data {
        var h = [UInt8](SHA512.hash(data: edSeed).prefix(32))
        h[0] &= 248
        h[31] = (h[31] & 127) | 64
        return Data(h)
    }

    /// X25519 public key for an Ed25519 public key: u = (1 + y) / (1 - y) mod p
    /// (crypto_sign_ed25519_pk_to_curve25519).
    public static func x25519Public(edPublic: Data) throws -> Data {
        guard edPublic.count == 32 else { throw CryptoError.badKey }
        var bytes = [UInt8](edPublic)
        bytes[31] &= 0x7f  // the top bit is the sign of the Edwards point, not part of y
        guard let y = Field25519(littleEndian: bytes) else { throw CryptoError.badKey }
        let den = Field25519.one - y
        guard !den.isZero else { throw CryptoError.badKey }
        return Data(((Field25519.one + y) * den.inverted()).littleEndian)
    }

    public static func dh(_ privateKey: Data, _ publicKey: Data) throws -> Data {
        let mine = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: privateKey)
        let theirs = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: publicKey)
        return try mine.sharedSecretFromKeyAgreement(with: theirs).withUnsafeBytes { Data($0) }
    }

    /// Identity key (Ed25519 public) for a 32-byte seed: what the server stores as `ik`.
    public static func ed25519Public(edSeed: Data) throws -> Data {
        try Curve25519.Signing.PrivateKey(rawRepresentation: edSeed).publicKey.rawRepresentation
    }

    public static func newPrivate() -> Data { Curve25519.KeyAgreement.PrivateKey().rawRepresentation }

    public static func publicOf(_ privateKey: Data) throws -> Data {
        try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: privateKey).publicKey.rawRepresentation
    }

    public static func sha256(_ parts: Data...) -> Data {
        var h = SHA256()
        for part in parts { h.update(data: part) }
        return Data(h.finalize())
    }

    public static func hmac(_ key: Data, _ parts: Data...) -> Data { hmac(key, parts) }

    static func hmac(_ key: Data, _ parts: [Data]) -> Data {
        var mac = HMAC<SHA256>(key: SymmetricKey(data: key.isEmpty ? Data(count: 32) : key))
        for part in parts { mac.update(data: part) }
        return Data(mac.finalize())
    }

    /// RFC 5869 HKDF-SHA256 (an empty salt means 32 zero bytes).
    public static func hkdf(ikm: Data, info: String, size: Int = 32, salt: Data = Data()) -> Data {
        let prk = hmac(salt, [ikm])
        var out = Data()
        var t = Data()
        var i: UInt8 = 1
        while out.count < size {
            t = hmac(prk, [t, Data(info.utf8), Data([i])])
            i += 1
            out += t
        }
        return out.prefix(size)
    }

    /// 32 random bytes from the system CSPRNG.
    public static func random32() -> Data { SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) } }
}

/// Unsigned lexicographic order, used to put two parties' values in a canonical order.
public func compareBytes(_ a: Data, _ b: Data) -> Int {
    let x = [UInt8](a), y = [UInt8](b)
    for i in 0..<min(x.count, y.count) where x[i] != y[i] { return Int(x[i]) - Int(y[i]) }
    return x.count - y.count
}

public enum Base64URL {
    public static func encode(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    public static func decode(_ text: String) -> Data? {
        var padded = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while padded.count % 4 != 0 { padded += "=" }
        return Data(base64Encoded: padded)
    }
}

public extension Data {
    var hex: String { map { String(format: "%02x", $0) }.joined() }
}
