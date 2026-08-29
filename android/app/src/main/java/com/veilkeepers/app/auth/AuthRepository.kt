package com.veilkeepers.app.auth

import com.veilkeepers.app.crypto.Argon2Kdf
import com.veilkeepers.app.crypto.AuthHash
import com.veilkeepers.app.crypto.KdfParams
import com.veilkeepers.app.crypto.VaultKey
import com.veilkeepers.app.data.ApiClient
import com.veilkeepers.app.data.AuthApi
import com.veilkeepers.app.data.HttpAuthApi
import com.veilkeepers.app.data.LoginResult
import com.veilkeepers.app.data.SessionStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Coarse progress phases so the UI can distinguish KDF work from network I/O. */
enum class AuthPhase { DERIVING, NETWORK }

/**
 * Implements the register/login/logout key flows exactly per spec-1.md §A.1.
 *
 * All KDF and crypto work runs on [Dispatchers.Default]; the plaintext VK is
 * only ever returned to the caller (memory) and never written to storage.
 *
 * [kdfParams] is injectable so JVM tests can run with tiny parameters; the
 * production default is the frozen spec value. [apiFactory] is injectable so
 * tests can supply a fake [AuthApi].
 */
class AuthRepository(
    private val storage: SessionStorage,
    private val kdfParams: KdfParams = KdfParams.SPEC,
    private val apiFactory: (baseUrl: String) -> AuthApi = { baseUrl ->
        HttpAuthApi(ApiClient(baseUrl))
    },
) {

    /**
     * Register flow (spec-1.md §A.1): salt → Argon2id → KEK + verifier →
     * auth_hash → generate VK → wrap(KEK) → POST register, then immediately
     * log in with the freshly derived material (no second derivation) so the
     * user lands on an unlocked vault.
     *
     * @return the plaintext VK — memory only, never persisted.
     */
    suspend fun register(
        serverUrl: String,
        username: String,
        password: CharArray,
        onPhase: (AuthPhase) -> Unit = {},
    ): ByteArray = withContext(Dispatchers.Default) {
        val base = normalizeUrl(serverUrl)
        storage.serverUrl = base
        val api = apiFactory(base)

        onPhase(AuthPhase.DERIVING)
        var salt: ByteArray? = null
        var derived: ByteArray? = null
        var kek: ByteArray? = null
        var verifier: ByteArray? = null
        var digest: ByteArray? = null
        var vaultKey: ByteArray? = null
        var wrapped: ByteArray? = null
        var returned = false
        try {
            salt = Argon2Kdf.randomSalt()
            derived = Argon2Kdf.derive(password, salt, kdfParams)
            val split = Argon2Kdf.split(derived)
            kek = split.first
            verifier = split.second
            digest = AuthHash.of(verifier)
            val authHashB64 = AuthHash.toBase64(digest)
            vaultKey = VaultKey.generate()
            wrapped = VaultKey.wrap(vaultKey, kek)

            onPhase(AuthPhase.NETWORK)
            api.register(
                username = username,
                authHashB64 = authHashB64,
                kdfSaltB64 = AuthHash.toBase64(salt),
                kdfParams = kdfParams,
                wrappedVaultKeyB64 = AuthHash.toBase64(wrapped),
            )
            // Reuse the derived verifier — saves a second 2–8 s derivation.
            val login = api.login(username, authHashB64, storage.deviceIdentifier, storage.deviceName())
            saveSession(base, username, login)
            returned = true
            vaultKey!!
        } finally {
            salt?.fill(0)
            derived?.fill(0)
            kek?.fill(0)
            verifier?.fill(0)
            digest?.fill(0)
            wrapped?.fill(0)
            if (!returned) vaultKey?.fill(0)
        }
    }

    /**
     * Login flow (spec-1.md §A.1): GET kdf → derive with the stored salt +
     * params → auth_hash → POST login → persist session → unwrap VK.
     *
     * @return the plaintext VK — memory only, never persisted.
     */
    suspend fun login(
        serverUrl: String,
        username: String,
        password: CharArray,
        onPhase: (AuthPhase) -> Unit = {},
    ): ByteArray = withContext(Dispatchers.Default) {
        val base = normalizeUrl(serverUrl)
        storage.serverUrl = base
        val api = apiFactory(base)

        onPhase(AuthPhase.NETWORK)
        val info = api.getKdf(username)

        onPhase(AuthPhase.DERIVING)
        var salt: ByteArray? = null
        var derived: ByteArray? = null
        var kek: ByteArray? = null
        var verifier: ByteArray? = null
        var digest: ByteArray? = null
        var wrappedBlob: ByteArray? = null
        try {
            salt = AuthHash.fromBase64(info.saltB64)
            derived = Argon2Kdf.derive(password, salt, info.params)
            val split = Argon2Kdf.split(derived)
            kek = split.first
            verifier = split.second
            digest = AuthHash.of(verifier)
            val authHashB64 = AuthHash.toBase64(digest)

            onPhase(AuthPhase.NETWORK)
            val login = api.login(username, authHashB64, storage.deviceIdentifier, storage.deviceName())
            wrappedBlob = AuthHash.fromBase64(login.wrappedVaultKeyB64)
            // Unwrap BEFORE persisting: a failed unlock never stores state.
            val vaultKey = VaultKey.unwrap(wrappedBlob, kek)
            saveSession(base, username, login)
            vaultKey
        } finally {
            salt?.fill(0)
            derived?.fill(0)
            kek?.fill(0)
            verifier?.fill(0)
            digest?.fill(0)
            wrappedBlob?.fill(0)
        }
    }

    /**
     * Revokes the server session and clears local session material. The local
     * store is cleared even when the network call fails (token may already be
     * expired/revoked; logout must never strand a session on-device).
     */
    suspend fun logout() {
        val token = storage.sessionToken
        val base = storage.serverUrl
        if (token.isNotEmpty() && base.isNotEmpty()) {
            try {
                apiFactory(normalizeUrl(base)).logout(token)
            } catch (ignored: Exception) {
                // Best effort: local wipe below is what matters.
            }
        }
        storage.clear()
    }

    private fun saveSession(baseUrl: String, username: String, login: LoginResult) {
        storage.serverUrl = baseUrl
        storage.username = username
        storage.sessionToken = login.sessionToken
        storage.wrappedVaultKeyB64 = login.wrappedVaultKeyB64
        storage.expiresAt = login.expiresAt
    }

    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        if (trimmed.isEmpty() ||
            !(trimmed.startsWith("http://") || trimmed.startsWith("https://"))
        ) {
            throw IllegalArgumentException("Server URL must start with http:// or https://.")
        }
        return trimmed
    }

    companion object {
        /** Default homelab server hint (spec environment). */
        const val DEFAULT_SERVER_URL = "http://192.168.50.131:18080"
    }
}
