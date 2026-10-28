package com.cim.securecall.crypto

import com.google.crypto.tink.subtle.X25519
import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Key conversions and primitives shared by the call handshake and QR verification.
 * Kept free of Android APIs so it runs in JVM unit tests.
 */
object Keys {
    private val P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))

    /** X25519 private scalar for an Ed25519 seed (same as libsodium crypto_sign_ed25519_sk_to_curve25519). */
    fun x25519Private(edSeed: ByteArray): ByteArray {
        val h = MessageDigest.getInstance("SHA-512").digest(edSeed).copyOf(32)
        h[0] = (h[0].toInt() and 248).toByte()
        h[31] = (h[31].toInt() and 127 or 64).toByte()
        return h
    }

    /** X25519 public key for an Ed25519 public key: u = (1 + y) / (1 - y) mod p (crypto_sign_ed25519_pk_to_curve25519). */
    fun x25519Public(edPublic: ByteArray): ByteArray {
        require(edPublic.size == 32) { "bad key" }
        val y = leToInt(edPublic.copyOf().also { it[31] = (it[31].toInt() and 0x7f).toByte() })
        val den = BigInteger.ONE.subtract(y).mod(P)
        require(y < P && den.signum() != 0) { "bad key" }
        val u = BigInteger.ONE.add(y).multiply(den.modInverse(P)).mod(P)
        return intToLe(u)
    }

    fun dh(privateKey: ByteArray, publicKey: ByteArray): ByteArray = X25519.computeSharedSecret(privateKey, publicKey)

    fun newPrivate(): ByteArray = X25519.generatePrivateKey()

    fun publicOf(privateKey: ByteArray): ByteArray = X25519.publicFromPrivate(privateKey)

    fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run { parts.forEach(::update); digest() }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key.takeIf { it.isNotEmpty() } ?: ByteArray(32), "HmacSHA256"))
            parts.forEach(::update)
            doFinal()
        }

    /** RFC 5869 HKDF-SHA256 (empty salt = 32 zero bytes). */
    fun hkdf(ikm: ByteArray, info: String, size: Int = 32, salt: ByteArray = ByteArray(0)): ByteArray {
        val prk = hmac(salt, ikm)
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < size) {
            t = hmac(prk, t, info.encodeToByteArray(), byteArrayOf(i++.toByte()))
            out.write(t)
        }
        return out.toByteArray().copyOf(size)
    }

    private fun leToInt(b: ByteArray) = BigInteger(1, b.reversedArray())

    /** 32 bytes little-endian (toByteArray() is big-endian and may carry a leading sign byte). */
    private fun intToLe(v: BigInteger): ByteArray = v.toByteArray().takeLast(32).toByteArray().reversedArray().copyOf(32)
}

/** Unsigned lexicographic order, used to put two parties' values in a canonical order. */
fun compareBytes(a: ByteArray, b: ByteArray): Int {
    for (i in 0 until minOf(a.size, b.size)) {
        val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
        if (d != 0) return d
    }
    return a.size - b.size
}
