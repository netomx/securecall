import CryptoKit
import XCTest
@testable import KalloqCore

/// The vectors of `tools/kalloq_crypto.py`, which interoperates with the `noiseprotocol` library.
/// They are the same ones `apps/android/.../crypto/CryptoTest.kt` checks: both apps have to agree
/// byte for byte or a call between an iPhone and an Android phone cannot start.
final class CryptoTests: XCTestCase {
    private let seedA = Data((0..<32).map { UInt8($0) })
    private let seedB = Data((0..<32).map { UInt8($0 + 32) })
    private let psk = Data(repeating: 7, count: 32)
    private let callId = "0e8f1c2a-3b4d-4e5f-8a6b-7c8d9e0f1a2b"

    private var ikA: Data { ed25519Public(seedA) }
    private var ikB: Data { ed25519Public(seedB) }

    private func ed25519Public(_ seed: Data) -> Data {
        try! Curve25519.Signing.PrivateKey(rawRepresentation: seed).publicKey.rawRepresentation
    }

    private func hs(_ initiator: Bool, psk: Data? = nil) throws -> NoiseHandshake {
        let (seed, peer, e) = initiator ? (seedA, ikB, UInt8(1)) : (seedB, ikA, UInt8(2))
        return try NoiseHandshake(initiator: initiator,
                                  s: Keys.x25519Private(edSeed: seed),
                                  rs: Keys.x25519Public(edPublic: peer),
                                  psk: psk ?? self.psk,
                                  prologue: CallCrypto.prologue(callId: callId),
                                  ephemeral: Data(repeating: e, count: 32))
    }

    func testKeyConversion() throws {
        XCTAssertEqual("03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8", ikA.hex)
        XCTAssertEqual("4701d08488451f545a409fb58ae3e58581ca40ac3f7f114698cd71deac73ca01",
                       try Keys.x25519Public(edPublic: ikA).hex)
        // The scalar derived from the seed and the point converted from the public key must agree.
        for _ in 0..<20 {
            let seed = Curve25519.Signing.PrivateKey().rawRepresentation
            let ik = ed25519Public(seed)
            XCTAssertEqual(try Keys.publicOf(Keys.x25519Private(edSeed: seed)),
                           try Keys.x25519Public(edPublic: ik))
        }
    }

    func testHandshakeMatchesReference() throws {
        let a = try hs(true)
        let b = try hs(false)
        let m1 = try a.writeOffer(Data(#"{"sdp":"offer"}"#.utf8))
        XCTAssertEqual(
            "a4e09292b651c278b9772c569f5fa9bb13d906b46ab68c9df9dc2b4409f8a20971ee8a571bc6d710734791bf01ee41a80b42381da5770625263b93100ae3af",
            m1.hex
        )
        XCTAssertEqual(#"{"sdp":"offer"}"#, String(decoding: try b.readOffer(m1)))

        let (m2, tb) = try b.writeAnswer(Data(#"{"sdp":"answer"}"#.utf8))
        XCTAssertEqual(
            "ce8d3ad1ccb633ec7b70c17814a5c76ecd029685050d344745ba05870e587d59df5328f672fecf5bb53c496aed540ee34433e82893e5d186123ec98efdcea72f",
            m2.hex
        )
        let (payload, ta) = try a.readAnswer(m2)
        XCTAssertEqual(#"{"sdp":"answer"}"#, String(decoding: payload))
        XCTAssertEqual("f6737f7a4ffcdb8503f5c54d7b8638e671f53577a9bfd383fb91a8bce6ddee6c", a.handshakeHash.hex)
        XCTAssertEqual(a.handshakeHash, b.handshakeHash)
        XCTAssertEqual("251 374", CallCrypto.callCode(handshakeHash: a.handshakeHash))

        let t1 = try ta.encrypt(Data("ice1".utf8))
        XCTAssertEqual("0000000000000000450f42979093daef276594de90af1534f73b79b7", t1.hex)
        XCTAssertEqual("ice1", String(decoding: try tb.decrypt(t1)))
        let t2 = try tb.encrypt(Data("ice2".utf8))
        XCTAssertEqual("0000000000000000e85521920990a92ffaaf0e4000eb9e560d6f60b8", t2.hex)
        XCTAssertEqual("ice2", String(decoding: try ta.decrypt(t2)))
        XCTAssertThrowsError(try ta.decrypt(t2))  // replay
    }

    func testWrongPskOrKeyFails() throws {
        let a = try hs(true, psk: CallCrypto.noPSK)
        let b = try hs(false)
        _ = try b.readOffer(try a.writeOffer(Data([1])))
        let (m2, _) = try b.writeAnswer(Data([2]))
        XCTAssertThrowsError(try a.readAnswer(m2))

        // An offer from someone else than the pinned peer does not decrypt.
        let mallory = try NoiseHandshake(initiator: true, s: Keys.newPrivate(),
                                         rs: Keys.x25519Public(edPublic: ikB), psk: psk,
                                         prologue: CallCrypto.prologue(callId: callId))
        let fromMallory = try mallory.writeOffer(Data([1]))
        XCTAssertThrowsError(try hs(false).readOffer(fromMallory))

        // Nor does an offer replayed under another call ID.
        let other = try NoiseHandshake(initiator: false, s: Keys.x25519Private(edSeed: seedB),
                                       rs: Keys.x25519Public(edPublic: ikA), psk: psk,
                                       prologue: CallCrypto.prologue(callId: "x"))
        let good = try hs(true).writeOffer(Data([1]))
        XCTAssertThrowsError(try other.readOffer(good))
    }

    func testQrPsk() throws {
        let rA = Data(repeating: 3, count: 32)
        let rB = Data(repeating: 4, count: 32)
        let pA = try Verification.psk(mySeed: seedA, myIk: ikA, myR: rA, peerIk: ikB, peerR: rB)
        let pB = try Verification.psk(mySeed: seedB, myIk: ikB, myR: rB, peerIk: ikA, peerR: rA)
        XCTAssertEqual("d622bb3a4bceb06d229b9f6107d8501e3eb143416d3e4381d2b861ac317e0357", pA.hex)
        XCTAssertEqual(pA, pB)
        XCTAssertEqual("327 137 295", Verification.code(psk: pA))
        XCTAssertEqual("84b582e26d39ec24", CallCrypto.pskId(pA))

        let text = Verification.encode(userId: callId, ik: ikA, r: rA)
        let code = Verification.decode(text)
        XCTAssertEqual(ikA, code?.ik)
        XCTAssertEqual(rA, code?.r)
        XCTAssertEqual(callId, code?.userId)
        XCTAssertNil(Verification.decode("kalloq-verify:1:" + callId + ":short:short"))
    }
}

private extension String {
    init(decoding data: Data) { self = String(decoding: data, as: UTF8.self) }
}
