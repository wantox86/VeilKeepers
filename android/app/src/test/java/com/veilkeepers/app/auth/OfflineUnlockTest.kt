package com.veilkeepers.app.auth

import com.veilkeepers.app.crypto.KdfParams
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

/** Tiny KDF params so unlock tests run instantly; production uses KdfParams.SPEC. */
private val TEST_PARAMS = KdfParams(m = 1024, t = 1, p = 1)

/** Records register payloads and replays them on getKdf/login (same fake style as AuthFlowsTest). */
private class OfflineFakeAuthApi : AuthApi {
    var registeredUsername: String? = null
    var registeredAuthHashB64: String? = null
    var registeredSaltB64: String? = null
    var registeredParams: KdfParams? = null
    var registeredWrappedB64: String? = null
    var loginCalls = 0

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
        if (username != registeredUsername || authHashB64 != registeredAuthHashB64) {
            throw ApiError.InvalidCredentials
        }
        loginCalls++
        return LoginResult(
            sessionToken = "tok-" + UUID.randomUUID(),
            wrappedVaultKeyB64 = registeredWrappedB64!!,
            expiresAt = "2026-09-28T00:00:00Z",
        )
    }

    override suspend fun logout(bearerToken: String) = Unit
}

/** In-memory [SessionStorage] for JVM tests (no Android Keystore). */
private class OfflineInMemoryStorage : SessionStorage {
    override var serverUrl: String = ""
    override var username: String = ""
    override var sessionToken: String = ""
    override var wrappedVaultKeyB64: String = ""
    override var expiresAt: String = ""
    override var biometricWrappedVkB64: String = ""
    override var autoLockPolicy: String = "IMMEDIATELY"
    override var biometricEnabled: Boolean = false
    override var kdfSaltB64: String = ""
    override var kdfParamsJson: String = ""
    private val deviceId: String = UUID.randomUUID().toString()
    override val deviceIdentifier: String get() = deviceId
    override fun deviceName(): String = "TestDevice"
    override fun clear() {
        username = ""
        sessionToken = ""
        wrappedVaultKeyB64 = ""
        expiresAt = ""
        biometricWrappedVkB64 = ""
        autoLockPolicy = "IMMEDIATELY"
        biometricEnabled = false
        kdfSaltB64 = ""
        kdfParamsJson = ""
    }
}

/**
 * Sprint 6 OFFLINE unlock path (spec.md §24/§25 soft lock): the cached kdf
 * salt + params (public inputs only) let [AuthRepository.unlockOffline]
 * re-derive the KEK and unwrap the stored VK with NO network round-trip.
 * Every failure surface raises [OfflineUnlockUnavailableException], which the
 * ViewModel maps to the transparent network-login fallback — never a
 * user-visible error on its own.
 */
class OfflineUnlockTest {

    private val storage = OfflineInMemoryStorage()
    private val api = OfflineFakeAuthApi()
    private val repository = AuthRepository(storage, TEST_PARAMS) { api }

    @Test
    fun registerCachesKdfMaterialForOfflineUnlock() = runBlocking {
        repository.register("http://server:18080", "alice", "s3cret!".toCharArray())

        // Salt cached is the client-generated salt sent at registration.
        assertEquals(api.registeredSaltB64, storage.kdfSaltB64)
        // Params cached as the canonical JSON encoding of the params in use.
        assertEquals(TEST_PARAMS.encode(), storage.kdfParamsJson)
    }

    @Test
    fun cachedSaltAndParamsUnlockTheSameVaultKeyOffline() = runBlocking {
        val originalVk = repository.register("http://server:18080", "bob", "pw-pw!".toCharArray())
        assertEquals(1, api.loginCalls) // register auto-login only

        // Soft-lock simulation: the VK left memory, session + cache remain.
        val vk = repository.unlockOffline("pw-pw!".toCharArray())

        assertArrayEquals(originalVk, vk)
        // Offline: the server was never contacted again.
        assertEquals(1, api.loginCalls)
    }

    @Test
    fun loginRefreshesTheKdfCacheFromTheKdfLookupResponse() = runBlocking {
        repository.register("http://server:18080", "carol", "pw".toCharArray())
        storage.kdfSaltB64 = ""
        storage.kdfParamsJson = ""

        repository.login("http://server:18080", "carol", "pw".toCharArray())

        // Refilled from the kdf_lookup response (kdf_salt / kdf_params fields).
        assertEquals(api.registeredSaltB64, storage.kdfSaltB64)
        assertEquals(TEST_PARAMS.encode(), storage.kdfParamsJson)
    }

    @Test
    fun wrongPasswordSignalsFallbackNotTheErrorDetail() = runBlocking {
        repository.register("http://server:18080", "dave", "right".toCharArray())
        try {
            repository.unlockOffline("wrong".toCharArray())
            fail("wrong password must raise the fallback signal")
        } catch (expected: OfflineUnlockUnavailableException) {
            // GCM tag failure is indistinguishable from any other failure —
            // the message never leaks which one happened.
        }
    }

    @Test
    fun missingCachePiecesEachSignalFallback() = runBlocking {
        for (wiper in listOf(
            { s: OfflineInMemoryStorage -> s.kdfSaltB64 = "" },
            { s: OfflineInMemoryStorage -> s.kdfParamsJson = "" },
            { s: OfflineInMemoryStorage -> s.wrappedVaultKeyB64 = "" },
        )) {
            repository.register("http://server:18080", "erin-" + UUID.randomUUID(), "pw".toCharArray())
            wiper(storage)
            try {
                repository.unlockOffline("pw".toCharArray())
                fail("incomplete cache must raise the fallback signal")
            } catch (expected: OfflineUnlockUnavailableException) {
                // expected — ViewModel falls back to full network login.
            }
        }
    }

    @Test
    fun logoutClearsTheKdfCacheSoOfflineUnlockIsUnavailable() = runBlocking {
        repository.register("http://server:18080", "frank", "pw".toCharArray())
        assertTrue(storage.kdfSaltB64.isNotEmpty())

        repository.logout()

        assertTrue(storage.kdfSaltB64.isEmpty())
        assertTrue(storage.kdfParamsJson.isEmpty())
        try {
            repository.unlockOffline("pw".toCharArray())
            fail("cleared cache must raise the fallback signal")
        } catch (expected: OfflineUnlockUnavailableException) {
            // expected
        }
    }

    @Test
    fun corruptCachedParamsSignalFallbackWithoutCrashing() = runBlocking {
        repository.register("http://server:18080", "gina", "pw".toCharArray())
        storage.kdfParamsJson = """{"m":-1,"t":0,"p":0}""" // outside DoS ceilings
        try {
            repository.unlockOffline("pw".toCharArray())
            fail("out-of-bounds params must raise the fallback signal")
        } catch (expected: OfflineUnlockUnavailableException) {
            // expected — parseFrom ceilings are enforced on cached input too.
        }
    }
}
