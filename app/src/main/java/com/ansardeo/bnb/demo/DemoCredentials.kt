package com.ansardeo.bnb.demo

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts / decrypts bytes. The real implementation uses an Android Keystore key that never leaves the secure store. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(data: ByteArray): ByteArray
    fun deleteKey()
}

/** Local (on-device) storage only; deliberately NOT named get/put/delete so it can never be confused with an HTTP verb. */
interface KeyValueStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun erase(key: String)
}

/** Demo credentials in memory. Never printed: toString is redacted. */
class DemoCredentials(val apiKey: String, val secret: String) {
    override fun toString(): String = "DemoCredentials(***)"
}

sealed class CredentialState {
    object None : CredentialState()
    data class Saved(val maskedKey: String) : CredentialState()
    /** Stored data exists but cannot be decrypted (for example the Keystore key was lost): the user must replace the keys. */
    object Unreadable : CredentialState()
}

/**
 * Demo API credential storage (logic only, JVM-testable). Both values are stored ENCRYPTED; the only plain text kept is a
 * masked key hint (first 4 + last 4 characters). The Secret Key is never returned to the UI and never logged.
 */
class DemoCredentialRepository(private val store: KeyValueStore, private val cipher: SecretCipher) {
    companion object {
        private const val K_API = "demo_api_key_enc"
        private const val K_SECRET = "demo_api_secret_enc"
        private const val K_HINT = "demo_api_key_hint"
        const val MIN_LEN = 16
        const val MAX_LEN = 256
    }

    /** @return an error message (never echoing the values) or null when the format is acceptable. */
    fun validate(apiKey: String, secret: String): String? {
        val k = apiKey.trim(); val s = secret.trim()
        if (k.isEmpty()) return "API Key is empty."
        if (s.isEmpty()) return "Secret Key is empty."
        if (k.length < MIN_LEN || k.length > MAX_LEN) return "API Key has an unexpected length."
        if (s.length < MIN_LEN || s.length > MAX_LEN) return "Secret Key has an unexpected length."
        val ok = Regex("[A-Za-z0-9]+")
        if (!ok.matches(k)) return "API Key may contain only letters and digits (remove spaces or line breaks)."
        if (!ok.matches(s)) return "Secret Key may contain only letters and digits (remove spaces or line breaks)."
        return null
    }

    fun save(apiKey: String, secret: String): String? {
        validate(apiKey, secret)?.let { return it }
        val k = apiKey.trim(); val s = secret.trim()
        return try {
            store.write(K_API, hex(cipher.encrypt(k.toByteArray(Charsets.UTF_8))))
            store.write(K_SECRET, hex(cipher.encrypt(s.toByteArray(Charsets.UTF_8))))
            store.write(K_HINT, mask(k))
            null
        } catch (e: Exception) {
            delete()
            "Could not store the keys securely on this device."
        }
    }

    fun state(): CredentialState {
        if (store.read(K_API) == null || store.read(K_SECRET) == null) return CredentialState.None
        return if (load() != null) CredentialState.Saved(store.read(K_HINT) ?: "****") else CredentialState.Unreadable
    }

    fun hasCredentials(): Boolean = store.read(K_API) != null && store.read(K_SECRET) != null

    /** For signing requests only. Null when nothing is stored or it cannot be decrypted. */
    fun load(): DemoCredentials? = try {
        val a = store.read(K_API); val s = store.read(K_SECRET)
        if (a == null || s == null) null
        else DemoCredentials(String(cipher.decrypt(unhex(a)), Charsets.UTF_8), String(cipher.decrypt(unhex(s)), Charsets.UTF_8))
    } catch (e: Exception) { null }

    fun delete() {
        store.erase(K_API); store.erase(K_SECRET); store.erase(K_HINT)
        try { cipher.deleteKey() } catch (_: Exception) {}
    }

    private fun mask(key: String): String = if (key.length >= 8) key.take(4) + "…" + key.takeLast(4) else "****"
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

class SharedPrefsStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun read(key: String): String? = prefs.getString(key, null)
    override fun write(key: String, value: String) { prefs.edit().putString(key, value).commit() }
    override fun erase(key: String) { prefs.edit().remove(key).commit() }
}

/** AES-256-GCM with a non-exportable Android Keystore key. Output = 12-byte IV + ciphertext. */
class KeystoreCipher(private val alias: String = "bnb_demo_api_key_v1") : SecretCipher {
    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        val ks = keyStore()
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)
    }

    override fun decrypt(data: ByteArray): ByteArray {
        require(data.size > 12) { "Corrupt data" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        return c.doFinal(data, 12, data.size - 12)
    }

    override fun deleteKey() { runCatching { keyStore().deleteEntry(alias) } }
}

/** Android wiring (single place). */
object DemoCredentialStore {
    private const val PREFS = "bnb_demo_api_credentials"
    fun repository(context: Context): DemoCredentialRepository =
        DemoCredentialRepository(SharedPrefsStore(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)), KeystoreCipher())
}

/** Last connection test result (non-secret) so the status survives screen changes. */
object DemoConnectionStatusStore {
    private const val PREFS = "bnb_demo_api_status"
    data class Status(val ok: Boolean, val summary: String, val steps: List<String>, val atMillis: Long)

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(c: Context, report: BinanceDemoApi.Report) {
        prefs(c).edit().putBoolean("ok", report.ok).putString("summary", report.summary)
            .putString("steps", report.steps.joinToString("\n") { (if (it.ok) "✓ " else "✗ ") + it.name + " — " + it.detail })
            .putLong("at", System.currentTimeMillis()).apply()
    }

    fun load(c: Context): Status? {
        val p = prefs(c)
        if (!p.contains("at")) return null
        return Status(p.getBoolean("ok", false), p.getString("summary", "") ?: "", (p.getString("steps", "") ?: "").split("\n").filter { it.isNotBlank() }, p.getLong("at", 0L))
    }

    fun clear(c: Context) { prefs(c).edit().clear().apply() }
}
