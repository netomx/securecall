package com.cim.securecall.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** base64url values. [psk] is set once both phones have scanned each other's QR. */
@Serializable
data class VerifyRecord(val ik: String, val rSelf: String, val rOther: String? = null, val psk: String? = null)

/**
 * Device-local state. Secrets (session token, identity seed) are encrypted with an
 * AES-256-GCM key that lives in the Android Keystore and never leaves it.
 */
class LocalStore(context: Context) {
    private val prefs = context.getSharedPreferences("kalloq", Context.MODE_PRIVATE)

    var token: String?
        get() = getSecret(KEY_TOKEN)?.decodeToString()
        set(value) = putSecret(KEY_TOKEN, value?.encodeToByteArray())

    var identitySeed: ByteArray?
        get() = getSecret(KEY_SEED)
        set(value) = putSecret(KEY_SEED, value)

    /** A seed is stored, even if [identitySeed] cannot decrypt it (Keystore key lost): never replace it silently. */
    val hasIdentitySeed: Boolean get() = prefs.contains(KEY_SEED)

    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    /** Names the user gave to contacts. Never sent to the server. */
    fun aliases(): Map<String, String> = readMap(KEY_ALIASES)
    fun setAlias(id: String, alias: String?) = updateMap(KEY_ALIASES) { if (alias.isNullOrBlank()) remove(id) else put(id, alias.trim()) }

    /** Identity keys pinned on first sight (TOFU), base64url. A different key from the server means "key changed". */
    fun pins(): Map<String, String> = readMap(KEY_PINS)
    fun setPin(id: String, ik: String) = updateMap(KEY_PINS) { put(id, ik) }

    /**
     * In-person (QR) verification per contact, encrypted: the peer's identity key it applies to,
     * our random value, theirs once scanned, and the resulting PSK.
     */
    fun verifications(): Map<String, VerifyRecord> =
        getSecret(KEY_VERIFY)?.let { runCatching { Json.decodeFromString<Map<String, VerifyRecord>>(it.decodeToString()) }.getOrNull() }
            .orEmpty()

    fun updateVerifications(change: MutableMap<String, VerifyRecord>.() -> Unit) {
        // Unreadable records are left alone rather than overwritten with an empty map.
        check(!prefs.contains(KEY_VERIFY) || getSecret(KEY_VERIFY) != null) { "verifications unreadable" }
        val map = verifications().toMutableMap().apply(change)
        putSecret(KEY_VERIFY, Json.encodeToString<Map<String, VerifyRecord>>(map).encodeToByteArray())
    }

    /**
     * A contact got a new user id. Copies (never moves, never overwrites) what we know about them, so the server
     * cannot use a rename to drop a pinned key or to put another contact's key on someone we already know.
     */
    fun renameContact(oldId: String, newId: String) {
        if (oldId == newId || newId in pins() || newId in aliases() || newId in verifications()) return
        updateMap(KEY_ALIASES) { get(oldId)?.let { put(newId, it) } }
        updateMap(KEY_PINS) { get(oldId)?.let { put(newId, it) } }
        runCatching { updateVerifications { get(oldId)?.let { put(newId, it) } } }
    }

    /** Incoming call ids already handled, so a replayed offer does not ring again. Last [SEEN_CALLS] only. */
    fun callSeen(callId: String): Boolean = prefs.getString(KEY_SEEN_CALLS, "").orEmpty().split(',').contains(callId)

    fun markCallSeen(callId: String) {
        val seen = prefs.getString(KEY_SEEN_CALLS, "").orEmpty().split(',').filter { it.isNotEmpty() && it != callId }
        prefs.edit().putString(KEY_SEEN_CALLS, (seen.takeLast(SEEN_CALLS - 1) + callId).joinToString(",")).apply()
    }

    fun clearSession() = prefs.edit().remove(KEY_TOKEN).apply()

    /** New account on this device: forget the previous identity and contacts. */
    fun resetAccount() = prefs.edit().remove(KEY_TOKEN).remove(KEY_SEED).remove(KEY_ALIASES).remove(KEY_PINS).remove(KEY_VERIFY)
        .remove(KEY_SEEN_CALLS).remove(KEY_USER_ID).remove(KEY_USERNAME).apply()

    private fun readMap(key: String): Map<String, String> {
        val raw = prefs.getString(key, null) ?: return emptyMap()
        return runCatching {
            Json.parseToJsonElement(raw).jsonObject.mapNotNull { (k, v) -> v.jsonPrimitive.contentOrNull?.let { k to it } }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun updateMap(key: String, change: MutableMap<String, String>.() -> Unit) {
        val map = readMap(key).toMutableMap().apply(change)
        prefs.edit().putString(key, JsonObject(map.mapValues { JsonPrimitive(it.value) }).toString()).apply()
    }

    private fun putSecret(key: String, plain: ByteArray?) {
        if (plain == null) {
            prefs.edit().remove(key).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val blob = cipher.iv + cipher.doFinal(plain)
        prefs.edit().putString(key, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    private fun getSecret(key: String): ByteArray? {
        val stored = prefs.getString(key, null) ?: return null
        return runCatching {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, blob, 0, IV_BYTES))
            cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
        }.getOrNull()
    }

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(MASTER_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(MASTER_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val MASTER_ALIAS = "kalloq_master"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val KEY_TOKEN = "token"
        const val KEY_SEED = "identity_seed"
        const val KEY_USER_ID = "user_id"
        const val KEY_USERNAME = "username"
        const val KEY_ALIASES = "aliases"
        const val KEY_PINS = "pins"
        const val KEY_VERIFY = "verifications"
        const val KEY_SEEN_CALLS = "seen_calls"
        const val SEEN_CALLS = 200
    }
}
