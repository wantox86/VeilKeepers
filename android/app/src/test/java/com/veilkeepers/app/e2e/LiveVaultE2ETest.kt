package com.veilkeepers.app.e2e

import com.veilkeepers.app.auth.AuthRepository
import com.veilkeepers.app.crypto.AuthHash
import com.veilkeepers.app.crypto.KdfParams
import com.veilkeepers.app.crypto.PayloadCipher
import com.veilkeepers.app.data.ApiClient
import com.veilkeepers.app.data.ApiError
import com.veilkeepers.app.data.HttpVaultApi
import com.veilkeepers.app.data.SessionStorage
import com.veilkeepers.app.vault.ItemPayload
import com.veilkeepers.app.vault.VaultField
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/**
 * Live vault end-to-end test against a running VeilKeepers backend.
 *
 * SKIPPED unless VK_E2E_BASE_URL is set, e.g.:
 *   VK_E2E_BASE_URL=http://192.168.50.131:18080 ./gradlew :app:testDebugUnitTest
 *
 * Vault routes are NOT rate-limited (docs/api/vault.md), so the vault part
 * can loop freely; only register/login/logout hit the rate-limited auth
 * routes (3 calls — comfortably inside the 10 req/min per-IP budget).
 *
 * Acceptance covered: create → encrypt → upload → retrieve → decrypt →
 * display, with byte-for-byte base64 round-trip and proof that the server
 * DB holds only ciphertext. Ephemeral vk<timestamp> accounts accumulate on
 * the live server — accepted, there is no account-cleanup endpoint yet.
 */
class LiveVaultE2ETest {

    /** In-memory [SessionStorage] for the JVM E2E run (no Android Keystore). */
    private class InMemoryStorage : SessionStorage {
        override var serverUrl: String = ""
        override var username: String = ""
        override var sessionToken: String = ""
        override var wrappedVaultKeyB64: String = ""
        override var expiresAt: String = ""
        private val deviceId: String = UUID.randomUUID().toString()
        override val deviceIdentifier: String get() = deviceId
        override fun deviceName(): String = "jvm-vault-e2e"
        override fun clear() {
            username = ""
            sessionToken = ""
            wrappedVaultKeyB64 = ""
            expiresAt = ""
        }
    }

    @Test(timeout = 600_000)
    fun fullVaultCycleAgainstLiveBackend() {
        val baseUrl = System.getenv("VK_E2E_BASE_URL")
        assumeTrue("VK_E2E_BASE_URL not set — skipping live E2E test", baseUrl != null)

        runBlocking {
            val storage = InMemoryStorage()
            val repository = AuthRepository(storage, KdfParams.SPEC)
            val username = "vk" + System.currentTimeMillis()
            val password = "veil-vault-e2e-${UUID.randomUUID()}".toCharArray()

            // ---- register (client generates VK, auto-login, seed categories)
            val vaultKey = repository.register(baseUrl!!, username, password)
            val seedWarning = repository.seedDefaultCategories(vaultKey)
            assertNull("category seeding must succeed against the live server", seedWarning)

            val api = HttpVaultApi(ApiClient(baseUrl), storage.sessionToken)

            // ---- the five default categories exist, encrypted client-side
            val categoryPage = api.listCategories()
            assertFalse(categoryPage.hasMore)
            assertEquals(5, categoryPage.categories.size)
            val decryptedNames = categoryPage.categories.map {
                PayloadCipher.decryptToString(AuthHash.fromBase64(it.encryptedNameB64), vaultKey)
            }.toSet()
            assertEquals(AuthRepository.DEFAULT_CATEGORY_NAMES.toSet(), decryptedNames)
            // Server stores opaque blobs: no plaintext name appears verbatim.
            categoryPage.categories.forEach { entry ->
                AuthRepository.DEFAULT_CATEGORY_NAMES.forEach { name ->
                    assertFalse(entry.encryptedNameB64.contains(name))
                }
            }

            // ---- create → encrypt → upload
            val title = "E2E notebook entry — derrière le voile 🔐"
            val notes = "multi-line\nnotes with UTF-8: 密码"
            val fields = listOf(
                VaultField("username", "e2e-user"),
                VaultField("password", "e2e-s3cret"),
            )
            val payload = ItemPayload.encode(title, notes, fields)
            val encryptedPayloadB64 = AuthHash.toBase64(
                PayloadCipher.encryptPayload(payload, vaultKey)
            )
            val firstCategory = categoryPage.categories.first().id
            val created = api.createItem(firstCategory, encryptedPayloadB64)
            assertEquals(firstCategory, created.categoryId)

            // ---- retrieve: list contains it, ordered updated_at DESC
            val listed = api.listItems()
            assertTrue(listed.items.any { it.id == created.id })
            assertEquals(created.id, listed.items.first().id)

            // ---- byte-for-byte base64 round-trip (server stores verbatim)
            val fetched = api.getItem(created.id)
            assertEquals(encryptedPayloadB64, fetched.encryptedPayloadB64)
            assertArrayEquals(
                encryptedPayloadB64.toByteArray(Charsets.US_ASCII),
                fetched.encryptedPayloadB64.toByteArray(Charsets.US_ASCII),
            )

            // ---- decrypt → display equals the original plaintext
            val blob = AuthHash.fromBase64(fetched.encryptedPayloadB64)
            val decrypted = ItemPayload.parse(PayloadCipher.decryptToString(blob, vaultKey))
            assertEquals(title, decrypted.title)
            assertEquals(notes, decrypted.notes)
            assertEquals(fields, decrypted.fields)

            // The server DB holds only ciphertext — no plaintext bytes in the blob.
            val blobText = String(blob, Charsets.ISO_8859_1)
            assertFalse(blobText.contains("e2e-user"))
            assertFalse(blobText.contains("e2e-s3cret"))

            // ---- update: full replacement, move to Uncategorized
            val updatedPayloadB64 = AuthHash.toBase64(
                PayloadCipher.encryptPayload(
                    ItemPayload.encode("updated title", "", emptyList()),
                    vaultKey,
                )
            )
            api.updateItem(created.id, null, updatedPayloadB64)
            val afterUpdate = api.getItem(created.id)
            assertNull(afterUpdate.categoryId)
            assertEquals(updatedPayloadB64, afterUpdate.encryptedPayloadB64)

            // ---- delete category → item survives under Uncategorized
            val tempNameB64 = AuthHash.toBase64(
                PayloadCipher.encryptName("E2E temp", vaultKey)
            )
            val tempCategory = api.createCategory(tempNameB64)
            api.updateItem(created.id, tempCategory.id, updatedPayloadB64)
            assertEquals(tempCategory.id, api.getItem(created.id).categoryId)

            api.deleteCategory(tempCategory.id)
            val survivor = api.getItem(created.id)
            assertNull("item must survive its category's deletion", survivor.categoryId)
            assertEquals(updatedPayloadB64, survivor.encryptedPayloadB64)

            // ---- delete item → 404 afterwards (ownership-hiding envelope)
            api.deleteItem(created.id)
            try {
                api.getItem(created.id)
                throw AssertionError("deleted item must not be retrievable")
            } catch (expected: ApiError.NotFound) {
                // expected
            }

            // ---- cleanup: revoke the session (also proves logout still works)
            repository.logout()
            assertTrue(storage.sessionToken.isEmpty())
        }
    }
}
