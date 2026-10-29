import Foundation

/**
 * In-person verification. Each phone shows a QR with its user ID, identity key and 32 random bytes,
 * and scans the other's. The random values never touch the server, so the resulting PSK is known
 * only to the two phones:
 *
 *   psk = HKDF(X25519(IK_a, IK_b) ‖ ik_1 ‖ r_1 ‖ ik_2 ‖ r_2, "kalloq/qr/1")   (ordered by identity key)
 */
public enum Verification {
    private static let prefix = "kalloq-verify:1:"

    public struct Code {
        public let userId: String
        public let ik: Data
        public let r: Data
    }

    public static func newRandom() -> Data { Keys.random32() }

    public static func encode(userId: String, ik: Data, r: Data) -> String {
        prefix + userId + ":" + Base64URL.encode(ik) + ":" + Base64URL.encode(r)
    }

    public static func decode(_ text: String) -> Code? {
        guard text.hasPrefix(prefix) else { return nil }
        let parts = text.dropFirst(prefix.count).components(separatedBy: ":")
        guard parts.count == 3,
              let ik = Base64URL.decode(parts[1]), ik.count == 32,
              let r = Base64URL.decode(parts[2]), r.count == 32 else { return nil }
        return Code(userId: parts[0].lowercased(), ik: ik, r: r)
    }

    public static func psk(mySeed: Data, myIk: Data, myR: Data, peerIk: Data, peerR: Data) throws -> Data {
        let dh = try Keys.dh(Keys.x25519Private(edSeed: mySeed), Keys.x25519Public(edPublic: peerIk))
        let mine = myIk + myR
        let theirs = peerIk + peerR
        let (first, second) = compareBytes(myIk, peerIk) < 0 ? (mine, theirs) : (theirs, mine)
        return Keys.hkdf(ikm: dh + first + second, info: "kalloq/qr/1")
    }

    /// Shown on both phones after scanning: equal codes mean both sides hold the same PSK.
    public static func code(psk: Data) -> String {
        digits(Keys.sha256(Data("kalloq/qr-code/1".utf8), psk), 9)
    }
}
