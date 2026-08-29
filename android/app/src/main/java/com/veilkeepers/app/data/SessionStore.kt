package com.veilkeepers.app.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * Persisted session surface. Declared as an interface so unit tests can
 * inject an in-memory fake; [EncryptedSessionStore] is the production
 * implementation backed by EncryptedSharedPreferences.
 */
interface SessionStorage {
    /** Homelab server base URL, e.g. http://192.168.50.131:18080. */
    var serverUrl: String

    /** Authenticated username (empty when signed out). */
    var username: String

    /** Raw bearer session token (empty when signed out). */
    var sessionToken: String

    /** base64(nonce || AES-256-GCM(VK)) exactly as stored server-side. */
    var wrappedVaultKeyB64: String

    /** Session expiry, RFC3339 string as returned by the backend. */
    var expiresAt: String

    /**
     * Stable random UUID generated once (spec-1.md §B.12). Survives [clear]
     * so the same device keeps the same identity.
     */
    val deviceIdentifier: String

    /** Build.MODEL (spec-1.md §B.12). */
    fun deviceName(): String

    /**
     * Wipes session material on logout. Keeps serverUrl (convenience) and
     * deviceIdentifier (stable device identity, §B.12).
     */
    fun clear()
}

/**
 * [SessionStorage] over androidx EncryptedSharedPreferences.
 *
 * Degradation path (docs/security/key-architecture.md §8): if the Android
 * Keystore is corrupt/unavailable, EncryptedSharedPreferences cannot be
 * created. Session material is then kept MEMORY-ONLY for the lifetime of
 * this instance — it is never written to plain SharedPreferences — so the
 * user simply has to log in again after process death. The decision is
 * sticky per instance and logged once, secret-free.
 */
class EncryptedSessionStore(context: Context) : SessionStorage {

    /** Sticky per-instance degradation flag decided once in the constructor. */
    private val fallback: Boolean
    private val prefs: SharedPreferences?

    /** Memory-only session material used when [fallback] is true. */
    private val memory = HashMap<String, String>()

    private val storedDeviceIdentifier: String

    init {
        var created: SharedPreferences? = null
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            created = EncryptedSharedPreferences.create(
                context,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            // ONE secret-free degradation message; no values are logged.
            Log.w(
                TAG,
                "Encrypted session store unavailable (Keystore failure); " +
                    "session material kept in memory only until process death.",
            )
        }
        prefs = created
        fallback = created == null

        // device_identifier (spec-1.md §B.12): generated and persisted exactly
        // once, synchronously committed — no racy lazy write. In fallback mode
        // the ID is stable for the process lifetime (never written to disk).
        storedDeviceIdentifier = if (fallback) {
            UUID.randomUUID().toString()
        } else {
            val existing = created.getString(KEY_DEVICE_IDENTIFIER, null)
            if (existing != null) {
                existing
            } else {
                val fresh = UUID.randomUUID().toString()
                created.edit().putString(KEY_DEVICE_IDENTIFIER, fresh).commit()
                fresh
            }
        }
    }

    override var serverUrl: String
        get() = read(KEY_SERVER_URL)
        set(value) = write(KEY_SERVER_URL, value)

    override var username: String
        get() = read(KEY_USERNAME)
        set(value) = write(KEY_USERNAME, value)

    override var sessionToken: String
        get() = read(KEY_SESSION_TOKEN)
        set(value) = write(KEY_SESSION_TOKEN, value)

    override var wrappedVaultKeyB64: String
        get() = read(KEY_WRAPPED_VAULT_KEY)
        set(value) = write(KEY_WRAPPED_VAULT_KEY, value)

    override var expiresAt: String
        get() = read(KEY_EXPIRES_AT)
        set(value) = write(KEY_EXPIRES_AT, value)

    override val deviceIdentifier: String
        get() = storedDeviceIdentifier

    override fun deviceName(): String = Build.MODEL

    override fun clear() {
        if (fallback) {
            memory.clear()
            return
        }
        // commit() — logout must durably wipe credentials before returning.
        prefs!!.edit()
            .remove(KEY_USERNAME)
            .remove(KEY_SESSION_TOKEN)
            .remove(KEY_WRAPPED_VAULT_KEY)
            .remove(KEY_EXPIRES_AT)
            .commit()
    }

    private fun read(key: String): String =
        if (fallback) memory[key] ?: "" else prefs!!.getString(key, "") ?: ""

    private fun write(key: String, value: String) {
        if (fallback) {
            memory[key] = value
            return
        }
        prefs!!.edit().putString(key, value).apply()
    }

    companion object {
        private const val TAG = "VeilSessionStore"
        private const val FILE_NAME = "veilkeepers_session"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_SESSION_TOKEN = "session_token"
        private const val KEY_WRAPPED_VAULT_KEY = "wrapped_vault_key"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_DEVICE_IDENTIFIER = "device_identifier"
    }
}
