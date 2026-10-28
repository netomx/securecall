package com.cim.securecall.crypto

import com.google.crypto.tink.subtle.Ed25519Sign
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Vectors from tools/kalloq_crypto.py, which interoperates with the `noiseprotocol` library. */
class CryptoTest {
    private val seedA = ByteArray(32) { it.toByte() }
    private val seedB = ByteArray(32) { (it + 32).toByte() }
    private val ikA = Ed25519Sign.KeyPair.newKeyPairFromSeed(seedA).publicKey
    private val ikB = Ed25519Sign.KeyPair.newKeyPairFromSeed(seedB).publicKey
    private val psk = ByteArray(32) { 7 }
    private val callId = "0e8f1c2a-3b4d-4e5f-8a6b-7c8d9e0f1a2b"

    private fun hs(initiator: Boolean, psk: ByteArray = this.psk): NoiseHandshake {
        val (seed, peer, e) = if (initiator) Triple(seedA, ikB, 1) else Triple(seedB, ikA, 2)
        return NoiseHandshake(initiator, Keys.x25519Private(seed), Keys.x25519Public(peer), psk,
            CallCrypto.prologue(callId), ephemeral = ByteArray(32) { e.toByte() })
    }

    @Test
    fun keyConversion() {
        assertEquals("03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8", ikA.toHex())
        assertEquals("4701d08488451f545a409fb58ae3e58581ca40ac3f7f114698cd71deac73ca01", Keys.x25519Public(ikA).toHex())
        repeat(20) {
            val seed = Ed25519Sign.KeyPair.newKeyPair().privateKey
            val ik = Ed25519Sign.KeyPair.newKeyPairFromSeed(seed).publicKey
            assertArrayEquals(Keys.publicOf(Keys.x25519Private(seed)), Keys.x25519Public(ik))
        }
    }

    @Test
    fun handshakeMatchesReference() {
        val a = hs(true)
        val b = hs(false)
        val m1 = a.writeOffer("""{"sdp":"offer"}""".encodeToByteArray())
        assertEquals(
            "a4e09292b651c278b9772c569f5fa9bb13d906b46ab68c9df9dc2b4409f8a20971ee8a571bc6d710734791bf01ee41a80b42381da5770625263b93100ae3af",
            m1.toHex()
        )
        assertEquals("""{"sdp":"offer"}""", b.readOffer(m1).decodeToString())
        val (m2, tb) = b.writeAnswer("""{"sdp":"answer"}""".encodeToByteArray())
        assertEquals(
            "ce8d3ad1ccb633ec7b70c17814a5c76ecd029685050d344745ba05870e587d59df5328f672fecf5bb53c496aed540ee34433e82893e5d186123ec98efdcea72f",
            m2.toHex()
        )
        val (payload, ta) = a.readAnswer(m2)
        assertEquals("""{"sdp":"answer"}""", payload.decodeToString())
        assertEquals("f6737f7a4ffcdb8503f5c54d7b8638e671f53577a9bfd383fb91a8bce6ddee6c", a.handshakeHash.toHex())
        assertArrayEquals(a.handshakeHash, b.handshakeHash)
        assertEquals("251 374", CallCrypto.callCode(a.handshakeHash))

        val t1 = ta.encrypt("ice1".encodeToByteArray())
        assertEquals("0000000000000000450f42979093daef276594de90af1534f73b79b7", t1.toHex())
        assertEquals("ice1", tb.decrypt(t1).decodeToString())
        val t2 = tb.encrypt("ice2".encodeToByteArray())
        assertEquals("0000000000000000e85521920990a92ffaaf0e4000eb9e560d6f60b8", t2.toHex())
        assertEquals("ice2", ta.decrypt(t2).decodeToString())
        assertThrows(IllegalArgumentException::class.java) { ta.decrypt(t2) }  // replay
    }

    @Test
    fun wrongPskOrKeyFails() {
        val a = hs(true, CallCrypto.NO_PSK)
        val b = hs(false)
        b.readOffer(a.writeOffer(byteArrayOf(1)))
        val (m2, _) = b.writeAnswer(byteArrayOf(2))
        assertThrows(Exception::class.java) { a.readAnswer(m2) }

        // An offer from someone else than the pinned peer does not decrypt.
        val mallory = NoiseHandshake(true, Keys.newPrivate(), Keys.x25519Public(ikB), psk, CallCrypto.prologue(callId))
        assertThrows(Exception::class.java) { hs(false).readOffer(mallory.writeOffer(byteArrayOf(1))) }

        // Nor does an offer replayed under another call ID.
        val other = NoiseHandshake(false, Keys.x25519Private(seedB), Keys.x25519Public(ikA), psk, CallCrypto.prologue("x"))
        assertThrows(Exception::class.java) { other.readOffer(hs(true).writeOffer(byteArrayOf(1))) }
    }

    @Test
    fun qrPsk() {
        val rA = ByteArray(32) { 3 }
        val rB = ByteArray(32) { 4 }
        val pA = Verification.psk(seedA, ikA, rA, ikB, rB)
        val pB = Verification.psk(seedB, ikB, rB, ikA, rA)
        assertEquals("d622bb3a4bceb06d229b9f6107d8501e3eb143416d3e4381d2b861ac317e0357", pA.toHex())
        assertArrayEquals(pA, pB)
        assertEquals("327 137 295", Verification.code(pA))
        assertEquals("84b582e26d39ec24", CallCrypto.pskId(pA))

        val text = Verification.encode("0e8f1c2a-3b4d-4e5f-8a6b-7c8d9e0f1a2b", ikA, rA)
        val code = Verification.decode(text)!!
        assertArrayEquals(ikA, code.ik)
        assertArrayEquals(rA, code.r)
    }
}
