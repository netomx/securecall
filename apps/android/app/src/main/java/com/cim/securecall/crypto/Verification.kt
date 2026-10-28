package com.cim.securecall.crypto

import java.security.SecureRandom
import java.util.Base64

/**
 * In-person verification. Each phone shows a QR with its user ID, identity key and 32 random bytes,
 * and scans the other's. The random values never touch the server, so the resulting PSK is known
 * only to the two phones:
 *
 *   psk = HKDF(X25519(IK_a, IK_b) ‖ ik_1 ‖ r_1 ‖ ik_2 ‖ r_2, "kalloq/qr/1")   (ordered by identity key)
 */
object Verification {
    private const val PREFIX = "kalloq-verify:1:"
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val b64d = Base64.getUrlDecoder()

    data class Code(val userId: String, val ik: ByteArray, val r: ByteArray)

    fun newRandom(): ByteArray = ByteArray(32).also(SecureRandom()::nextBytes)

    fun encode(userId: String, ik: ByteArray, r: ByteArray): String = PREFIX + userId + ":" + b64.encodeToString(ik) + ":" + b64.encodeToString(r)

    fun decode(text: String): Code? {
        if (!text.startsWith(PREFIX)) return null
        val parts = text.removePrefix(PREFIX).split(":")
        if (parts.size != 3) return null
        return runCatching {
            Code(parts[0].lowercase(), b64d.decode(parts[1]), b64d.decode(parts[2]))
                .takeIf { it.ik.size == 32 && it.r.size == 32 }
        }.getOrNull()
    }

    fun psk(mySeed: ByteArray, myIk: ByteArray, myR: ByteArray, peerIk: ByteArray, peerR: ByteArray): ByteArray {
        val dh = Keys.dh(Keys.x25519Private(mySeed), Keys.x25519Public(peerIk))
        val (first, second) = if (compareBytes(myIk, peerIk) < 0) (myIk + myR) to (peerIk + peerR) else (peerIk + peerR) to (myIk + myR)
        return Keys.hkdf(dh + first + second, "kalloq/qr/1")
    }

    /** Shown on both phones after scanning: equal codes mean both sides hold the same PSK. */
    fun code(psk: ByteArray): String = digits(Keys.sha256("kalloq/qr-code/1".encodeToByteArray(), psk), 9)
}
