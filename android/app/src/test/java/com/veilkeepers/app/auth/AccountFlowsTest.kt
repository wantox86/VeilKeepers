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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

private val TEST_PARAMS = KdfParams(m = 1024, t = 1, p = 1)

private class FakeAuthApiWithChangePassword : AuthApi {
    var registeredUsername: String? = null
    var registeredAuthHashB64: String? = null
    var registeredSaltB64: String? = null
    var registeredParams: KdfParams? = null
    var registeredWrappedB64: String? = null
    var changePasswordCalls = 0
    var lastCurrentAuthHashB64: String? = null
    var lastNewAuthHashB64: String? = null
    var lastNewKdfSaltB64: String? = null
    var lastNewWrappedB64: String? = null
    var changePasswordError: ApiError? = null

    override suspend fun getKdf(username: String): KdfInfo =
        KdfInfo(registeredSaltB64!!, registeredParams!!)

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
        if (authHashB64 != registeredAuthHashB64) throw ApiError.InvalidCredentials
        return LoginResult("tok-" + UUID.randomUUID(), registeredWrappedB64!!, "2026-12-31T00:00:00Z")
    }

    override suspend fun logout(bearerToken: String) {}

    override suspend fun changePassword(
        currentAuthHashB64: String,
        authHashB64: String,
        kdfSaltB64: String,
        kdfParams: KdfParams,
        wrappedVaultKeyB64: String,
        bearerToken: String,
    ) {
        changePasswordError?.let { throw it }
        if (currentAuthHashB64 != registeredAuthHashB64) throw ApiError.InvalidCredentials
        changePasswordCalls++
        lastCurrentAuthHashB64 = currentAuthHashB64
        lastNewAuthHashB64 = authHashB64
        lastNewKdfSaltB64 = kdfSaltB64
        lastNewWrappedB64 = wrappedVaultKeyB64
        registeredAuthHashB64 = authHashB64
        registeredSaltB64 = kdfSaltB64
        registeredWrappedB64 = wrappedVaultKeyB64
    }
}

private class InMemStorage : SessionStorage {
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
        kdfSaltB64 = ""
        kdfParamsJson = ""
    }
}

class AccountFlowsTest {

    private val storage = InMemStorage()
    private val api = FakeAuthApiWithChangePassword()
    private val repository = AuthRepository(storage, TEST_PARAMS) { api }

    @Test
    fun changePasswordReWrapsVkWithNewKekAndUpdatesCache() = runBlocking {
        val vk = repository.register("http://server:18080", "alice", "old-pw!".toCharArray())
        val oldSalt = storage.kdfSaltB64
        val oldWrapped = storage.wrappedVaultKeyB64

        repository.changePassword("old-pw!".toCharArray(), "new-pw!".toCharArray(), vk)

        assertEquals(1, api.changePasswordCalls)
        assertNotEquals(oldSalt, storage.kdfSaltB64)
        assertNotEquals(oldWrapped, storage.wrappedVaultKeyB64)

        // The new wrapped blob must decrypt to the SAME VK.
        val newWrappedBytes = AuthHash.fromBase64(storage.wrappedVaultKeyB64)
        val newSaltBytes = AuthHash.fromBase64(storage.kdfSaltB64)
        val newDerived = Argon2Kdf.derive("new-pw!".toByteArray(), newSaltBytes, TEST_PARAMS)
        val (newKek, _) = Argon2Kdf.split(newDerived)
        assertArrayEquals(vk, VaultKey.unwrap(newWrappedBytes, newKek))
    }

    @Test
    fun changePasswordWithWrongCurrentThrowsInvalidCredentials() = runBlocking {
        repository.register("http://server:18080", "bob", "right-pw".toCharArray())
        val vk = VaultKey.generate()
        try {
            repository.changePassword("wrong-pw".toCharArray(), "new".toCharArray(), vk)
            fail("wrong current password must throw")
        } catch (expected: ApiError.InvalidCredentials) {
            // expected
        }
    }

    @Test
    fun changePasswordUpdatesServerAuthHash() = runBlocking {
        repository.register("http://server:18080", "carol", "pw1".toCharArray())
        val vk = VaultKey.generate()
        val oldAuthHash = api.registeredAuthHashB64

        repository.changePassword("pw1".toCharArray(), "pw2".toCharArray(), vk)

        assertNotEquals(oldAuthHash, api.registeredAuthHashB64)
        assertEquals(api.registeredAuthHashB64, api.lastNewAuthHashB64)
    }

    @Test
    fun changePasswordWithoutLoginThrows() = runBlocking {
        val vk = VaultKey.generate()
        try {
            repository.changePassword("pw".toCharArray(), "pw2".toCharArray(), vk)
            fail("not logged in must throw")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("Not logged in"))
        }
    }
}
