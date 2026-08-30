package com.veilkeepers.app.auth

import com.veilkeepers.app.crypto.AuthHash
import com.veilkeepers.app.crypto.KdfParams
import com.veilkeepers.app.crypto.PayloadCipher
import com.veilkeepers.app.data.ApiError
import com.veilkeepers.app.data.AuthApi
import com.veilkeepers.app.data.CategoryEntry
import com.veilkeepers.app.data.CategoryListResult
import com.veilkeepers.app.data.ItemEntry
import com.veilkeepers.app.data.ItemListResult
import com.veilkeepers.app.data.KdfInfo
import com.veilkeepers.app.data.LoginResult
import com.veilkeepers.app.data.SessionStorage
import com.veilkeepers.app.data.VaultApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Tiny KDF params so flow tests run instantly; production uses KdfParams.SPEC. */
private val TEST_PARAMS = KdfParams(m = 1024, t = 1, p = 1)

/** Records register payloads and replays them on getKdf/login. */
private class SeedingFakeAuthApi : AuthApi {
    var registeredUsername: String? = null
    var registeredWrappedB64: String? = null

    override suspend fun getKdf(username: String): KdfInfo = throw ApiError.NotFound

    override suspend fun register(
        username: String,
        authHashB64: String,
        kdfSaltB64: String,
        kdfParams: KdfParams,
        wrappedVaultKeyB64: String,
    ) {
        registeredUsername = username
        registeredWrappedB64 = wrappedVaultKeyB64
    }

    override suspend fun login(
        username: String,
        authHashB64: String,
        deviceIdentifier: String,
        deviceName: String,
    ): LoginResult {
        if (username != registeredUsername) throw ApiError.InvalidCredentials
        return LoginResult(
            sessionToken = "tok-" + UUID.randomUUID(),
            wrappedVaultKeyB64 = registeredWrappedB64!!,
            expiresAt = "2026-09-28T00:00:00Z",
        )
    }

    override suspend fun logout(bearerToken: String) = Unit
}

/** Records seeded category blobs; can be forced to fail like a live outage. */
private class SeedingVaultApi : VaultApi {
    val createdEncryptedNames = mutableListOf<String>()
    var failure: ApiError? = null

    override suspend fun createCategory(encryptedNameB64: String): CategoryEntry {
        failure?.let { throw it }
        createdEncryptedNames.add(encryptedNameB64)
        return CategoryEntry(
            id = createdEncryptedNames.size.toLong(),
            encryptedNameB64 = encryptedNameB64,
            itemCount = 0,
            createdAt = "2026-08-30T00:00:00Z",
            updatedAt = "2026-08-30T00:00:00Z",
        )
    }

    override suspend fun listCategories(): CategoryListResult = throw UnsupportedOperationException()
    override suspend fun updateCategory(id: Long, encryptedNameB64: String) = throw UnsupportedOperationException()
    override suspend fun deleteCategory(id: Long) = throw UnsupportedOperationException()
    override suspend fun listItems(categoryId: Long?): ItemListResult = throw UnsupportedOperationException()
    override suspend fun createItem(categoryId: Long?, encryptedPayloadB64: String): ItemEntry =
        throw UnsupportedOperationException()

    override suspend fun getItem(id: Long): ItemEntry = throw UnsupportedOperationException()
    override suspend fun updateItem(id: Long, categoryId: Long?, encryptedPayloadB64: String) =
        throw UnsupportedOperationException()

    override suspend fun deleteItem(id: Long) = throw UnsupportedOperationException()
}

/** In-memory [SessionStorage] for JVM tests (no Android Keystore). */
private class SeedingInMemoryStorage : SessionStorage {
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
 * Register-time default-category seeding (spec-1.md §A.3: created by the
 * CLIENT at registration). New test file on purpose — the Sprint 3 auth
 * tests stay untouched.
 */
class RegisterSeedingTest {

    private val storage = SeedingInMemoryStorage()
    private val authApi = SeedingFakeAuthApi()
    private val vaultApi = SeedingVaultApi()
    private val repository = AuthRepository(
        storage,
        TEST_PARAMS,
        apiFactory = { authApi },
        vaultApiFactory = { _, _ -> vaultApi },
    )

    @Test
    fun registerSeedsTheFiveEncryptedDefaultCategories() = runBlocking {
        val vk = repository.register("http://server:18080", "alice", "s3cret!".toCharArray())

        assertNull(repository.seedDefaultCategories(vk))
        assertEquals(
            AuthRepository.DEFAULT_CATEGORY_NAMES,
            listOf("Common", "Work", "Tools", "Personal", "Other"),
        )
        assertEquals(5, vaultApi.createdEncryptedNames.size)

        // Each seeded blob decrypts with the freshly generated VK...
        val names = vaultApi.createdEncryptedNames.map {
            PayloadCipher.decryptToString(AuthHash.fromBase64(it), vk)
        }
        assertEquals(AuthRepository.DEFAULT_CATEGORY_NAMES, names)

        // ...and the server never saw a plaintext name.
        vaultApi.createdEncryptedNames.forEach { b64 ->
            val text = String(AuthHash.fromBase64(b64), Charsets.ISO_8859_1)
            AuthRepository.DEFAULT_CATEGORY_NAMES.forEach { name ->
                assertFalse(text.contains(name))
            }
        }
    }

    @Test
    fun seedingFailureSurfacesWarningButRegisterSucceeds() = runBlocking {
        vaultApi.failure = ApiError.ServerUnavailable

        val vk = repository.register("http://server:18080", "bob", "pw-pw!".toCharArray())
        val warning = repository.seedDefaultCategories(vk)

        // Non-fatal: the account exists, the session is stored, and the
        // warning is a single generic display-ready string (no details).
        assertNotNull(warning)
        assertEquals(AuthRepository.CATEGORY_SEED_WARNING, warning)
        assertEquals("bob", storage.username)
        assertTrue(storage.sessionToken.isNotEmpty())
        assertEquals(0, vaultApi.createdEncryptedNames.size)
    }

    @Test
    fun partialSeedingFailureStillYieldsTheWarning() = runBlocking {
        // Fail mid-way: two categories exist, then the connection dies. The
        // seeding is best-effort — duplicates on a later retry are fine.
        val vk = repository.register("http://server:18080", "carol", "pw".toCharArray())
        vaultApi.createCategory("irrelevant") // one succeeds
        vaultApi.failure = ApiError.Network(null)

        val warning = repository.seedDefaultCategories(vk)
        assertEquals(AuthRepository.CATEGORY_SEED_WARNING, warning)
    }

    @Test
    fun seedingWithoutASessionYieldsTheWarning() = runBlocking {
        val warning = repository.seedDefaultCategories(com.veilkeepers.app.crypto.VaultKey.generate())
        assertEquals(AuthRepository.CATEGORY_SEED_WARNING, warning)
        assertEquals(0, vaultApi.createdEncryptedNames.size)
    }
}
