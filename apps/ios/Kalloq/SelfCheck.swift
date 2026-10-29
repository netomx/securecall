import Foundation
import KalloqCore

/**
 * The reference vectors, run inside the app. They are the ones in `tools/kalloq_crypto.py` and in
 * Android's `CryptoTest.kt`, so a green list here means the end-to-end layer compiled into this
 * build speaks exactly what an Android phone speaks. The full set lives in KalloqCore's tests;
 * this is the part worth showing on screen while there is no account or call yet.
 */
enum SelfCheck {
    struct Row: Identifiable {
        let id = UUID()
        let name: String
        let expected: String
        let got: String
        var ok: Bool { expected == got }
    }

    private static let seedA = Data((0..<32).map { UInt8($0) })
    private static let seedB = Data((0..<32).map { UInt8($0 + 32) })
    private static let psk = Data(repeating: 7, count: 32)
    private static let callId = "0e8f1c2a-3b4d-4e5f-8a6b-7c8d9e0f1a2b"

    static func run() -> [Row] {
        do {
            let ikA = try Keys.ed25519Public(edSeed: seedA)
            let ikB = try Keys.ed25519Public(edSeed: seedB)

            let a = try handshake(initiator: true, seed: seedA, peer: ikB)
            let b = try handshake(initiator: false, seed: seedB, peer: ikA)
            let offer = try a.writeOffer(Data(#"{"sdp":"offer"}"#.utf8))
            _ = try b.readOffer(offer)
            let (answer, _) = try b.writeAnswer(Data(#"{"sdp":"answer"}"#.utf8))
            let (payload, _) = try a.readAnswer(answer)

            let rA = Data(repeating: 3, count: 32)
            let rB = Data(repeating: 4, count: 32)
            let qrPsk = try Verification.psk(mySeed: seedA, myIk: ikA, myR: rA, peerIk: ikB, peerR: rB)

            return [
                Row(name: String(localized: "Llave de identidad Ed25519"),
                    expected: "03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8",
                    got: ikA.hex),
                Row(name: String(localized: "Conversión a X25519"),
                    expected: "4701d08488451f545a409fb58ae3e58581ca40ac3f7f114698cd71deac73ca01",
                    got: try Keys.x25519Public(edPublic: ikA).hex),
                Row(name: String(localized: "Oferta cifrada (Noise mensaje 1)"),
                    expected: "a4e09292b651c278b9772c569f5fa9bb13d906b46ab68c9df9dc2b4409f8a20971ee8a571bc6d710734791bf01ee41a80b42381da5770625263b93100ae3af",
                    got: offer.hex),
                Row(name: String(localized: "Respuesta descifrada"),
                    expected: #"{"sdp":"answer"}"#,
                    got: String(decoding: payload, as: UTF8.self)),
                Row(name: String(localized: "Hash del handshake"),
                    expected: "f6737f7a4ffcdb8503f5c54d7b8638e671f53577a9bfd383fb91a8bce6ddee6c",
                    got: a.handshakeHash.hex),
                Row(name: String(localized: "Código de seguridad de la llamada"),
                    expected: "251 374",
                    got: CallCrypto.callCode(handshakeHash: a.handshakeHash)),
                Row(name: String(localized: "PSK de verificación por QR"),
                    expected: "d622bb3a4bceb06d229b9f6107d8501e3eb143416d3e4381d2b861ac317e0357",
                    got: qrPsk.hex),
                Row(name: String(localized: "Código de verificación"),
                    expected: "327 137 295",
                    got: Verification.code(psk: qrPsk)),
            ]
        } catch {
            return [Row(name: String(localized: "Error al ejecutar los vectores"), expected: "—", got: "\(error)")]
        }
    }

    /// Fixed ephemeral keys, as in the reference vectors; a real call uses a fresh one each time.
    private static func handshake(initiator: Bool, seed: Data, peer: Data) throws -> NoiseHandshake {
        try NoiseHandshake(initiator: initiator,
                           s: Keys.x25519Private(edSeed: seed),
                           rs: Keys.x25519Public(edPublic: peer),
                           psk: psk,
                           prologue: CallCrypto.prologue(callId: callId),
                           ephemeral: Data(repeating: initiator ? 1 : 2, count: 32))
    }
}
