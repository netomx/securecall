package com.cim.securecall.data

import android.util.Base64
import com.google.crypto.tink.subtle.Ed25519Sign
import java.security.MessageDigest

/** Ed25519 identity keys. The X25519 form used for key agreement is derived from the same key (phase 4). */
object Identity {
    fun newSeed(): ByteArray = Ed25519Sign.KeyPair.newKeyPair().privateKey

    fun publicKey(seed: ByteArray): ByteArray = Ed25519Sign.KeyPair.newKeyPairFromSeed(seed).publicKey

    /** First 128 bits of SHA-256(ik) as 8 groups of 4 hex digits, to compare in person. */
    fun fingerprint(ik: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(ik).take(16)
            .joinToString("") { "%02X".format(it) }
            .chunked(4).joinToString(" ")

    fun fingerprint(ikB64: String): String? = b64urlDecode(ikB64)?.let(::fingerprint)
}

private const val B64URL = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

fun b64url(bytes: ByteArray): String = Base64.encodeToString(bytes, B64URL)

fun b64urlDecode(text: String): ByteArray? = runCatching { Base64.decode(text, B64URL) }.getOrNull()

private val UUID_RE = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")

/** Accepts a bare ID, an invite link (https://call.l8.nz/a/<id>) or kalloq://add/<id>. */
fun parseFriendId(input: String): String? = UUID_RE.find(input.trim())?.value?.lowercase()

const val SERVER_URL = "wss://call.l8.nz/ws"
const val INVITE_BASE = "https://call.l8.nz/a/"
