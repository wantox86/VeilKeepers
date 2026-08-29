package com.veilkeepers.app.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
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
     * Stable random UUID created once at first access (spec-1.md §B.12).
     * Survives [clear] so the same device keeps the same identity.
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
 * Initialization is wrapped in try/catch because Android Keystore can be in
 * a broken state (factory reset bugs, OEM quirks); in that edge case the
 * store degrades to plain SharedPreferences so the app stays usable. The
 * device never holds the VK at rest either way, and logout [clear]s the
 * session immediately.
 */
class EncryptedSessionStore(context: Context) : SessionStorage {

    private val prefs: SharedPreferences = createPrefs(context)

    override var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "") ?: ""
        set(value) = prefs.editValue { putString(KEY_SERVER_URL, value) }

    override var username: String
        get() = prefs.getString(KEY_USERNAME, "") ?: ""
        set(value) = prefs.editValue { putString(KEY_USERNAME, value) }

    override var sessionToken: String
        get() = prefs.getString(KEY_SESSION_TOKEN, "") ?: ""
        set(value) = prefs.editValue { putString(KEY_SESSION_TOKEN, value) }

    override var wrappedVaultKeyB64: String
        get() = prefs.getString(KEY_WRAPPED_VAULT_KEY, "") ?: ""
        set(value) = prefs.editValue { putString(KEY_WRAPPED_VAULT_KEY, value) }

    override var expiresAt: String
        get() = prefs.getString(KEY_EXPIRES_AT, "") ?: ""
        set(value) = prefs.editValue { putString(KEY_EXPIRES_AT, value) }

    override val deviceIdentifier: String
        get() {
            prefs.getString(KEY_DEVICE_IDENTIFIER, null)?.let { return it }
            val fresh = UUID.randomUUID().toString()
            prefs.editValue { putString(KEY_DEVICE_IDENTIFIER, fresh) }
            return fresh
        }

    override fun deviceName(): String = Build.MODEL

    override fun clear() {
        prefs.editValue {
            remove(KEY_USERNAME)
            remove(KEY_SESSION_TOKEN)
            remove(KEY_WRAPPED_VAULT_KEY)
            remove(KEY_EXPIRES_AT)
        }
    }

    private fun createPrefs(context: Context): SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        // Keystore unavailable/corrupt: graceful fallback (see class doc).
        context.getSharedPreferences(FALLBACK_FILE_NAME, Context.MODE_PRIVATE)
    }

    private inline fun SharedPreferences.editValue(block: SharedPreferences.Editor.() -> Unit) {
        edit().apply(block).apply()
    }

    companion object {
        private const val FILE_NAME = "veilkeepers_session"
        private const val FALLBACK_FILE_NAME = "veilkeepers_session_fallback"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_SESSION_TOKEN = "session_token"
        private const val KEY_WRAPPED_VAULT_KEY = "wrapped_vault_key"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_DEVICE_IDENTIFIER = "device_identifier"
    }
}
