package com.cim.securecall.crypto

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

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
 */
class NoiseHandshake(
    private val initiator: Boolean,
    private val s: ByteArray,          // our static X25519 private key
    private val rs: ByteArray,         // their static X25519 public key
    private val psk: ByteArray,
    prologue: ByteArray,
    private val ephemeral: ByteArray = Keys.newPrivate(),  // fixed only in tests
) {
    private var ck: ByteArray
    private var h: ByteArray
    private val cipher = CipherState()
    private var re: ByteArray? = null
    private var step = 0

    init {
        require(psk.size == 32) { "psk" }
        h = PROTOCOL.encodeToByteArray().also { check(it.size == 32) }
        ck = h
        mixHash(prologue)
        val sPub = Keys.publicOf(s)
        // Pre-messages: initiator's static first, then responder's.
        if (initiator) { mixHash(sPub); mixHash(rs) } else { mixHash(rs); mixHash(sPub) }
    }

    /** Handshake hash once finished: identical on both sides, used for the call's comparison code. */
    val handshakeHash: ByteArray get() = h.copyOf()

    /** Message 1 (initiator). */
    fun writeOffer(payload: ByteArray): ByteArray {
        check(initiator && step == 0)
        step = 1
        val ePub = Keys.publicOf(ephemeral)
        mixHash(ePub); mixKey(ePub)
        mixKey(Keys.dh(ephemeral, rs))  // es
        mixKey(Keys.dh(s, rs))          // ss
        return ePub + encryptAndHash(payload)
    }

    /** Message 1 (responder). Throws if it was not made by the pinned peer for this prologue. */
    fun readOffer(message: ByteArray): ByteArray {
        check(!initiator && step == 0)
        step = 1
        require(message.size >= 32 + TAG) { "short" }
        val e = message.copyOf(32).also { re = it }
        mixHash(e); mixKey(e)
        mixKey(Keys.dh(s, e))   // es
        mixKey(Keys.dh(s, rs))  // ss
        return decryptAndHash(message.copyOfRange(32, message.size))
    }

    /** Message 2 (responder). Returns the message and the transport keys. */
    fun writeAnswer(payload: ByteArray): Pair<ByteArray, Transport> {
        check(!initiator && step == 1)
        step = 2
        val ePub = Keys.publicOf(ephemeral)
        mixHash(ePub); mixKey(ePub)
        mixKey(Keys.dh(ephemeral, re!!))  // ee
        mixKey(Keys.dh(ephemeral, rs))    // se
        mixKeyAndHash(psk)
        val msg = ePub + encryptAndHash(payload)
        return msg to split()
    }

    /** Message 2 (initiator). Throws if the peer is not who we pinned, or the PSKs differ. */
    fun readAnswer(message: ByteArray): Pair<ByteArray, Transport> {
        check(initiator && step == 1)
        step = 2
        require(message.size >= 32 + TAG) { "short" }
        val e = message.copyOf(32)
        mixHash(e); mixKey(e)
        mixKey(Keys.dh(ephemeral, e))  // ee
        mixKey(Keys.dh(s, e))          // se
        mixKeyAndHash(psk)
        val payload = decryptAndHash(message.copyOfRange(32, message.size))
        return payload to split()
    }

    private fun mixHash(data: ByteArray) {
        h = Keys.sha256(h, data)
    }

    private fun mixKey(ikm: ByteArray) {
        val (newCk, k) = noiseHkdf(ck, ikm, 2)
        ck = newCk
        cipher.init(k)
    }

    private fun mixKeyAndHash(ikm: ByteArray) {
        val (newCk, th, k) = noiseHkdf(ck, ikm, 3)
        ck = newCk
        mixHash(th)
        cipher.init(k)
    }

    private fun encryptAndHash(plain: ByteArray): ByteArray = cipher.encrypt(h, plain).also(::mixHash)

    private fun decryptAndHash(ct: ByteArray): ByteArray = cipher.decrypt(h, ct).also { mixHash(ct) }

    private fun split(): Transport {
        val (k1, k2) = noiseHkdf(ck, ByteArray(0), 2)
        val (send, recv) = if (initiator) k1 to k2 else k2 to k1
        return Transport(CipherState().apply { init(send) }, CipherState().apply { init(recv) })
    }

    companion object {
        const val PROTOCOL = "Noise_KKpsk2_25519_AESGCM_SHA256"
        const val TAG = 16

        private fun noiseHkdf(ck: ByteArray, ikm: ByteArray, n: Int): List<ByteArray> {
            val temp = Keys.hmac(ck, ikm)
            val out = ArrayList<ByteArray>(n)
            var prev = ByteArray(0)
            for (i in 1..n) {
                prev = Keys.hmac(temp, prev, byteArrayOf(i.toByte()))
                out += prev
            }
            return out
        }
    }
}

/**
 * Transport messages after the handshake (ICE candidates). Each carries its 8-byte nonce in clear so
 * that a lost or reordered message does not break the rest; a nonce must be higher than the last one.
 */
class Transport internal constructor(private val send: CipherState, private val recv: CipherState) {
    private var lastReceived = -1L

    fun encrypt(plain: ByteArray): ByteArray {
        val n = send.nonce
        return ByteBuffer.allocate(8).putLong(n).array() + send.encrypt(ByteArray(0), plain)
    }

    fun decrypt(message: ByteArray): ByteArray {
        require(message.size >= 8 + NoiseHandshake.TAG) { "short" }
        val n = ByteBuffer.wrap(message, 0, 8).long
        require(n > lastReceived) { "replay" }
        val plain = recv.decryptAt(n, ByteArray(0), message.copyOfRange(8, message.size))
        lastReceived = n
        return plain
    }
}

internal class CipherState {
    private var k: ByteArray? = null
    var nonce = 0L
        private set

    fun init(key: ByteArray) {
        k = key.copyOf(32)
        nonce = 0
    }

    fun encrypt(ad: ByteArray, plain: ByteArray): ByteArray {
        val key = k ?: return plain
        return aes(Cipher.ENCRYPT_MODE, key, nonce++, ad, plain)
    }

    fun decrypt(ad: ByteArray, ct: ByteArray): ByteArray {
        val key = k ?: return ct
        return aes(Cipher.DECRYPT_MODE, key, nonce++, ad, ct)
    }

    fun decryptAt(n: Long, ad: ByteArray, ct: ByteArray): ByteArray = aes(Cipher.DECRYPT_MODE, k!!, n, ad, ct)

    private fun aes(mode: Int, key: ByteArray, n: Long, ad: ByteArray, data: ByteArray): ByteArray {
        // Noise AESGCM nonce: 4 zero bytes then the counter, big-endian.
        val iv = ByteBuffer.allocate(12).putInt(0).putLong(n).array()
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            updateAAD(ad)
            doFinal(data)
        }
    }
}
