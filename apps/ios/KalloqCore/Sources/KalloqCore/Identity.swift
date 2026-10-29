import CryptoKit
import Foundation

/// Ed25519 identity keys. The X25519 form used for key agreement is derived from the same key.
public enum Identity {
    public static func newSeed() -> Data { Curve25519.Signing.PrivateKey().rawRepresentation }

    public static func publicKey(seed: Data) throws -> Data { try Keys.ed25519Public(edSeed: seed) }

    /// First 128 bits of SHA-256(ik) as 8 groups of 4 hex digits, to compare in person.
    public static func fingerprint(_ ik: Data) -> String {
        let hex = Keys.sha256(ik).prefix(16).map { String(format: "%02X", $0) }.joined()
        return stride(from: 0, to: hex.count, by: 4).map { start in
            let from = hex.index(hex.startIndex, offsetBy: start)
            let to = hex.index(from, offsetBy: 4)
            return String(hex[from..<to])
        }.joined(separator: " ")
    }

    public static func fingerprint(base64url: String) -> String? {
        Base64URL.decode(base64url).map(fingerprint)
    }
}

public enum Server {
    public static let url = "wss://call.l8.nz/ws"
    public static let inviteBase = "https://call.l8.nz/a/"
}

private let uuidPattern = try! NSRegularExpression(
    pattern: "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")

/// Accepts a bare ID, an invite link (https://call.l8.nz/a/<id>) or kalloq://add/<id>.
public func parseFriendId(_ input: String) -> String? {
    let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
    let range = NSRange(text.startIndex..., in: text)
    guard let match = uuidPattern.firstMatch(in: text, range: range),
          let found = Range(match.range, in: text) else { return nil }
    return String(text[found]).lowercased()
}
