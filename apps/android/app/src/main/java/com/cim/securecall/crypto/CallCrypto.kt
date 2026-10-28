package com.cim.securecall.crypto

/**
 * The end-to-end layer of a call. The SDP offer and answer travel inside the two Noise handshake
 * messages, so they are encrypted and authenticated by both identity keys. The DTLS fingerprints
 * in the SDP are therefore the real ones, and WebRTC's DTLS-SRTP keys are shared only by the two
 * phones: neither the server nor TURN can decrypt or swap the audio.
 */
object CallCrypto {
    /** PSK for contacts not verified in person (security then rests on the pinned identity keys). */
    val NO_PSK = ByteArray(32)

    fun prologue(callId: String): ByteArray = "kalloq/call/1\u0000$callId".encodeToByteArray()

    fun handshake(initiator: Boolean, mySeed: ByteArray, peerIk: ByteArray, psk: ByteArray?, callId: String) =
        NoiseHandshake(initiator, Keys.x25519Private(mySeed), Keys.x25519Public(peerIk), psk ?: NO_PSK, prologue(callId))

    /** Short ID of the PSK in use, sent inside the encrypted offer so a mismatch is reported clearly. */
    fun pskId(psk: ByteArray?): String =
        if (psk == null) "none" else Keys.sha256("kalloq/psk-id/1".encodeToByteArray(), psk).copyOf(8).toHex()

    /** Code both people see during the call; if it matches, nobody is in the middle. */
    fun callCode(handshakeHash: ByteArray): String = digits(Keys.sha256("kalloq/call-code/1".encodeToByteArray(), handshakeHash), 6)
}

/** [count] decimal digits from a hash, grouped in threes. */
internal fun digits(hash: ByteArray, count: Int): String {
    var v = java.math.BigInteger(1, hash.copyOf(8))
    val sb = StringBuilder()
    repeat(count) {
        val (q, r) = v.divideAndRemainder(java.math.BigInteger.TEN)
        sb.append(r.toInt())
        v = q
    }
    return sb.toString().chunked(3).joinToString(" ")
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
