package com.veilkeepers.app.auth

import com.veilkeepers.app.crypto.Argon2Kdf
import com.veilkeepers.app.crypto.AuthHash
import com.veilkeepers.app.crypto.KdfParams
import com.veilkeepers.app.crypto.PayloadCipher
import com.veilkeepers.app.crypto.VaultKey
import com.veilkeepers.app.data.ApiClient
import com.veilkeepers.app.data.AuthApi
import com.veilkeepers.app.data.HttpAuthApi
import com.veilkeepers.app.data.HttpVaultApi
import com.veilkeepers.app.data.LoginResult
import com.veilkeepers.app.data.SessionStorage
import com.veilkeepers.app.data.VaultApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Coarse progress phases so the UI can distinguish KDF work from network I/O. */
enum class AuthPhase { DERIVING, NETWORK }

/**
 * Signals that the OFFLINE unlock path cannot complete (missing kdf salt /
 * params cache, missing wrapped VK blob, or a derivation/unwrap failure).
 * The ViewModel treats this as "transparently fall back to the full network
 * login flow" — never a user-visible error by itself.
 */
class OfflineUnlockUnavailableException(message: String) : Exception(message)

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
    // [apiFactory] MUST stay the LAST parameter: existing callers (e.g. the
    // Sprint 3 AuthFlowsTest) pass it as a trailing lambda.
    private val vaultApiFactory: (baseUrl: String, bearerToken: String) -> VaultApi = { baseUrl, token ->
        HttpVaultApi(ApiClient(baseUrl), token)
    },
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
            // Sprint 6: cache the KDF material for offline unlock (spec.md §25
            // soft-lock path). The salt was generated client-side above.
            saveKdfCache(AuthHash.toBase64(salt), kdfParams)
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
            // Sprint 6: cache the KDF material from the kdf_lookup response
            // (AuthApi field names: kdf_salt / kdf_params) for offline unlock.
            saveKdfCache(info.saltB64, info.params)
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
     * Sprint 6 OFFLINE unlock (spec.md §24/§25 soft lock): re-derives the KEK
     * from the CACHED kdf salt + params (written on the last successful
     * login/register) and unwraps the locally stored wrapped VK — no network
     * round-trip, the server session stays untouched.
     *
     * @return the plaintext VK — memory only.
     * @throws OfflineUnlockUnavailableException when the cache is incomplete
     * or derivation/unwrap fails; the caller (AuthViewModel) transparently
     * falls back to the full network login flow in that case.
     */
    suspend fun unlockOffline(password: CharArray): ByteArray = withContext(Dispatchers.Default) {
        val saltB64 = storage.kdfSaltB64
        val paramsJson = storage.kdfParamsJson
        val wrappedB64 = storage.wrappedVaultKeyB64
        if (saltB64.isEmpty() || paramsJson.isEmpty() || wrappedB64.isEmpty()) {
            throw OfflineUnlockUnavailableException("offline unlock cache incomplete")
        }
        var salt: ByteArray? = null
        var derived: ByteArray? = null
        var kek: ByteArray? = null
        var wrappedBlob: ByteArray? = null
        try {
            salt = AuthHash.fromBase64(saltB64)
            // parseFrom enforces the DoS ceilings on the cached (server-origin)
            // params exactly like the login flow.
            val params = KdfParams.parseFrom(paramsJson)
            derived = Argon2Kdf.derive(password, salt, params)
            kek = Argon2Kdf.split(derived).first
            wrappedBlob = AuthHash.fromBase64(wrappedB64)
            VaultKey.unwrap(wrappedBlob, kek)
        } catch (e: OfflineUnlockUnavailableException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Sprint 6 F4: structured cancellation must propagate unchanged —
            // never disguised as "offline unavailable" (that would kick the
            // ViewModel into a network login on a dead scope).
            throw e
        } catch (e: Exception) {
            // Wrong password (GCM tag failure), corrupt cache or params —
            // never leak which; the ViewModel falls back to network login.
            throw OfflineUnlockUnavailableException("offline unlock failed")
        } finally {
            salt?.fill(0)
            derived?.fill(0)
            kek?.fill(0)
            wrappedBlob?.fill(0)
        }
    }

    /**
     * Seeds the five default categories (spec-1.md §A.3: created by the
     * CLIENT at registration, encrypted with the freshly generated VK).
     * Called right after a successful register (AuthViewModel orchestrates
     * register + seed as one registration flow).
     *
     * Best-effort WITH an error surface — the chosen "smallest" design: if
     * seeding fails the account still exists and register is considered a
     * success, so this returns a display-ready warning string
     * ([CATEGORY_SEED_WARNING]) instead of throwing. Returns null when all
     * five categories were created. Never throws, never logs blob material.
     */
    suspend fun seedDefaultCategories(vaultKey: ByteArray): String? {
        val token = storage.sessionToken
        val base = storage.serverUrl
        if (token.isEmpty() || base.isEmpty()) return CATEGORY_SEED_WARNING
        return try {
            val api = vaultApiFactory(normalizeUrl(base), token)
            for (name in DEFAULT_CATEGORY_NAMES) {
                api.createCategory(AuthHash.toBase64(PayloadCipher.encryptName(name, vaultKey)))
            }
            null
        } catch (e: Exception) {
            // Non-fatal: the account exists and the user can create
            // categories from the vault. One generic message, no details.
            CATEGORY_SEED_WARNING
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

    /**
     * Sprint 6: persists the KDF salt + params used by the successful auth so
     * [unlockOffline] can re-derive the KEK with no network round-trip. The
     * cache holds NO secret material: salt and params are public inputs, the
     * password/KEK/VK never touch storage.
     */
    private fun saveKdfCache(saltB64: String, params: KdfParams) {
        storage.kdfSaltB64 = saltB64
        storage.kdfParamsJson = params.encode()
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

        /** Default categories seeded client-side at registration (spec-1.md §A.3). */
        val DEFAULT_CATEGORY_NAMES = listOf("Common", "Work", "Tools", "Personal", "Other")

        /** Non-fatal warning surfaced when category seeding fails after a successful register. */
        const val CATEGORY_SEED_WARNING =
            "Account created, but the starter categories could not be added. " +
                "You can create them from the vault."
    }
}
