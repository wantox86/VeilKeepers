package com.veilkeepers.app.auth

import com.veilkeepers.app.crypto.Argon2Kdf
import com.veilkeepers.app.crypto.AuthHash
import com.veilkeepers.app.crypto.KdfParams
import com.veilkeepers.app.crypto.VaultKey
import com.veilkeepers.app.data.ApiError
import com.veilkeepers.app.data.AuthApi
import com.veilkeepers.app.data.KdfInfo
import com.veilkeepers.app.data.LoginResult
import com.veilkeepers.app.data.SessionStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

/** Tiny KDF params so flow tests run instantly; production uses KdfParams.SPEC. */
private val TEST_PARAMS = KdfParams(m = 1024, t = 1, p = 1)

/** Records register payloads and replays them on getKdf/login. */
private class FakeAuthApi : AuthApi {
    var registeredUsername: String? = null
    var registeredAuthHashB64: String? = null
    var registeredSaltB64: String? = null
    var registeredParams: KdfParams? = null
    var registeredWrappedB64: String? = null
    var loginCalls = 0
    var logoutCalls = 0
    var lastLoginDeviceIdentifier: String? = null
    var registerError: ApiError? = null
    var loginError: ApiError? = null

    override suspend fun getKdf(username: String): KdfInfo {
        val salt = registeredSaltB64 ?: throw ApiError.NotFound
        return KdfInfo(salt, registeredParams!!)
    }

    override suspend fun register(
        username: String,
        authHashB64: String,
        kdfSaltB64: String,
        kdfParams: KdfParams,
        wrappedVaultKeyB64: String,
    ) {
        registerError?.let { throw it }
        registeredUsername = username
        registeredAuthHashB64 = authHashB64
        registeredSaltB64 = kdfSaltB64
        registeredParams = kdfParams
        registeredWrappedB64 = wrappedVaultKeyB64
    }

    override suspend fun login(
        username: String,
        authHashB64: String,
        deviceIdentifier: String,
        deviceName: String,
    ): LoginResult {
        loginError?.let { throw it }
        if (username != registeredUsername || authHashB64 != registeredAuthHashB64) {
            throw ApiError.InvalidCredentials
        }
        loginCalls++
        lastLoginDeviceIdentifier = deviceIdentifier
        return LoginResult(
            sessionToken = "tok-" + UUID.randomUUID(),
            wrappedVaultKeyB64 = registeredWrappedB64!!,
            expiresAt = "2026-09-28T00:00:00Z",
        )
    }

    override suspend fun logout(bearerToken: String) {
        logoutCalls++
    }
}

/** In-memory [SessionStorage] for JVM tests (no Android Keystore). */
private class InMemoryStorage : SessionStorage {
    override var serverUrl: String = ""
    override var username: String = ""
    override var sessionToken: String = ""
    override var wrappedVaultKeyB64: String = ""
    override var expiresAt: String = ""
    private val deviceId: String = UUID.randomUUID().toString()
    override val deviceIdentifier: String get() = deviceId
    override fun deviceName(): String = "TestDevice"
    override fun clear() {
        username = ""
        sessionToken = ""
        wrappedVaultKeyB64 = ""
        expiresAt = ""
    }
}

class AuthFlowsTest {

    private val storage = InMemoryStorage()
    private val api = FakeAuthApi()
    private val repository = AuthRepository(storage, TEST_PARAMS) { api }

    @Test
    fun registerFlowSendsPayloadWithinBackendBounds() = runBlocking {
        val vk = repository.register("http://server:18080", "alice", "s3cret!".toCharArray())

        // salt: exactly 16 bytes (backend: ≥16, ≤32).
        val salt = AuthHash.fromBase64(api.registeredSaltB64!!)
        assertEquals(16, salt.size)

        // auth_hash: exactly 32 bytes (backend authHashBytes).
        val authHash = AuthHash.fromBase64(api.registeredAuthHashB64!!)
        assertEquals(32, authHash.size)

        // wrapped_vault_key: 60 bytes nonce||ciphertext (backend allows ≤128).
        val wrapped = AuthHash.fromBase64(api.registeredWrappedB64!!)
        assertTrue(wrapped.size <= 128)
        assertEquals(VaultKey.WRAPPED_BYTES, wrapped.size)

        assertEquals(TEST_PARAMS, api.registeredParams)

        // The returned VK must be the one inside the wrapped blob: re-derive
        // KEK from the captured salt and unwrap.
        val derived = Argon2Kdf.derive("s3cret!".toByteArray(), salt, TEST_PARAMS)
        val (kek, _) = Argon2Kdf.split(derived)
        assertArrayEquals(vk, VaultKey.unwrap(wrapped, kek))

        // Register auto-logs-in, so the session is stored.
        assertEquals("alice", storage.username)
        assertTrue(storage.sessionToken.isNotEmpty())
        assertEquals(api.registeredWrappedB64, storage.wrappedVaultKeyB64)
    }

    @Test
    fun loginFlowStoresSessionAndReturnsUnwrappedVk() = runBlocking {
        val originalVk = repository.register("http://server:18080", "bob", "pw-pw!".toCharArray())
        storage.clear()

        val vk = repository.login("http://server:18080/", "bob", "pw-pw!".toCharArray())

        assertArrayEquals(originalVk, vk)
        assertEquals("bob", storage.username)
        assertTrue(storage.sessionToken.isNotEmpty())
        assertEquals(api.registeredWrappedB64, storage.wrappedVaultKeyB64)
        assertEquals("2026-09-28T00:00:00Z", storage.expiresAt)
        assertEquals("http://server:18080", storage.serverUrl) // trailing slash trimmed
        assertEquals(storage.deviceIdentifier, api.lastLoginDeviceIdentifier)
    }

    @Test
    fun loginWithWrongPasswordFailsWithInvalidCredentials() = runBlocking {
        repository.register("http://server:18080", "carol", "right".toCharArray())
        try {
            repository.login("http://server:18080", "carol", "wrong".toCharArray())
            fail("wrong password must throw")
        } catch (expected: ApiError.InvalidCredentials) {
            // expected — the fake rejects mismatched auth_hash like the backend
        }
    }

    @Test
    fun logoutCallsApiAndClearsStore() = runBlocking {
        repository.register("http://server:18080", "dave", "pw".toCharArray())
        assertTrue(storage.sessionToken.isNotEmpty())

        repository.logout()

        assertEquals(1, api.logoutCalls)
        assertTrue(storage.sessionToken.isEmpty())
        assertTrue(storage.username.isEmpty())
        assertTrue(storage.wrappedVaultKeyB64.isEmpty())
        assertTrue(storage.expiresAt.isEmpty())
    }

    @Test
    fun logoutClearsStoreEvenWhenNetworkFails() = runBlocking {
        repository.register("http://server:18080", "erin", "pw".toCharArray())

        // Simulate a dead server on logout: swap in an API that always throws.
        val broken = AuthRepository(storage, TEST_PARAMS) {
            object : AuthApi by api {
                override suspend fun logout(bearerToken: String) {
                    throw ApiError.Network(null)
                }
            }
        }
        broken.logout()
        assertTrue(storage.sessionToken.isEmpty())
    }

    @Test
    fun everyApiErrorCodeMapsToTheRightUiMessage() {
        val messages = listOf(
            ApiError.InvalidCredentials,
            ApiError.UsernameTaken,
            ApiError.RateLimited,
            ApiError.RegistrationClosed,
            ApiError.InvalidInput,
            ApiError.NotFound,
            ApiError.Internal,
            ApiError.SessionExpired,
            ApiError.ServerUnavailable,
            ApiError.Network(null),
        ).map { errorUiMessage(it) }

        // Distinct, non-empty user-facing strings.
        messages.forEach { assertTrue(it.isNotBlank()) }
        assertEquals(messages.toSet().size, messages.size)

        // rate_limited gets the friendly "retry after ~1 minute" guidance.
        assertTrue(errorUiMessage(ApiError.RateLimited).contains("minute", ignoreCase = true))

        // Non-API failures degrade to the generic message.
        assertEquals(
            "Something went wrong. Please try again.",
            errorUiMessage(RuntimeException("boom")),
        )
    }

    @Test
    fun apiErrorFromCodeMapsAllBackendCodes() {
        assertEquals(ApiError.InvalidCredentials, ApiError.fromCode("invalid_credentials"))
        assertEquals(ApiError.UsernameTaken, ApiError.fromCode("username_taken"))
        assertEquals(ApiError.RateLimited, ApiError.fromCode("rate_limited"))
        assertEquals(ApiError.RegistrationClosed, ApiError.fromCode("registration_closed"))
        assertEquals(ApiError.InvalidInput, ApiError.fromCode("invalid_input"))
        assertEquals(ApiError.NotFound, ApiError.fromCode("not_found"))
        assertEquals(ApiError.Internal, ApiError.fromCode("internal_error"))
        // Session middleware codes (backend/internal/auth/middleware.go).
        assertEquals(ApiError.SessionExpired, ApiError.fromCode("invalid_token"))
        assertEquals(ApiError.ServerUnavailable, ApiError.fromCode("service_unavailable"))
        assertEquals(ApiError.Internal, ApiError.fromCode("mystery_code"))
    }

    @Test
    fun malformedServerUrlIsRejectedWithClearMessage() = runBlocking {
        for (badUrl in listOf("ftp://server:18080", "server:18080", "   ", "")) {
            try {
                repository.login(badUrl, "alice", "pw".toCharArray())
                fail("expected rejection of URL '$badUrl'")
            } catch (expected: IllegalArgumentException) {
                assertTrue(
                    errorUiMessage(expected).startsWith("Server URL must start with"),
                )
            }
        }
    }
}
