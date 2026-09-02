package com.veilkeepers.app.e2e

import com.veilkeepers.app.auth.AuthRepository
import com.veilkeepers.app.crypto.AuthHash
import com.veilkeepers.app.crypto.KdfParams
import com.veilkeepers.app.crypto.PayloadCipher
import com.veilkeepers.app.data.ApiClient
import com.veilkeepers.app.data.ApiError
import com.veilkeepers.app.data.HttpVaultApi
import com.veilkeepers.app.data.SessionStorage
import com.veilkeepers.app.data.VaultPayloads
import com.veilkeepers.app.vault.ItemPayload
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
 * Live attachment end-to-end test (Sprint 8) against a running VeilKeepers
 * backend.
 *
 * SKIPPED unless VK_E2E_BASE_URL is set, e.g.:
 *   VK_E2E_BASE_URL=http://192.168.50.131:18080 ./gradlew :app:testDebugUnitTest
 *
 * Acceptance covered (spec-1.md §F row 8): "File tersimpan terenkripsi, tidak
 * bisa dibuka langsung" — the uploaded body is opaque on the wire and on the
 * server (no PNG magic, no plaintext), yet decrypts byte-for-byte back to the
 * original image. Also pins the raw octet-stream upload/download, the LIST
 * endpoint, the encrypted-filename opacity, item-scoped 404s, and delete.
 */
class LiveAttachmentE2ETest {

    /** In-memory [SessionStorage] for the JVM E2E run (no Android Keystore). */
    private class AttachmentE2EStorage : SessionStorage {
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
        override fun deviceName(): String = "jvm-attachment-e2e"
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

    @Test(timeout = 600_000)
    fun attachmentCycleAgainstLiveBackend() {
        val baseUrl = System.getenv("VK_E2E_BASE_URL")
        assumeTrue("VK_E2E_BASE_URL not set — skipping live attachment E2E test", baseUrl != null)

        runBlocking {
            val storage = AttachmentE2EStorage()
            val repository = AuthRepository(storage, KdfParams.SPEC)
            val username = "vkatt" + System.currentTimeMillis()
            val password = "veil-attach-e2e-${UUID.randomUUID()}".toCharArray()

            val vaultKey = repository.register(baseUrl!!, username, password)
            assertNull("category seeding must succeed", repository.seedDefaultCategories(vaultKey))

            val api = HttpVaultApi(ApiClient(baseUrl), storage.sessionToken)

            // ---- create an item to hang the attachment on
            val category = api.listCategories().categories.first()
            val payloadB64 = AuthHash.toBase64(
                PayloadCipher.encryptPayload(ItemPayload.encode("Attachment host", "", emptyList()), vaultKey)
            )
            val item = api.createItem(category.id, payloadB64)

            // ---- a plaintext "image": a real PNG signature + pseudo-random body
            val pngSignature = byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            )
            val plaintext = pngSignature + ByteArray(8192) { (it * 37 % 256).toByte() }
            assertTrue("sanity: plaintext really starts with the PNG magic", plaintext.startsWith(pngSignature))

            val ciphertext = PayloadCipher.encryptAttachment(plaintext, vaultKey)
            assertEquals(plaintext.size + PayloadCipher.CIPHER_OVERHEAD_BYTES, ciphertext.size)
            val filename = "veiled-photo 密码.png"
            val encryptedFilenameB64Url = VaultPayloads.toBase64Url(
                PayloadCipher.encryptFilename(filename, vaultKey)
            )

            // ---- upload (raw octet-stream body; metadata in the query string)
            val created = api.uploadAttachment(item.id, "image/png", encryptedFilenameB64Url, ciphertext)
            assertEquals(item.id, created.vaultItemId)
            assertEquals("image/png", created.mimeType)
            assertEquals(ciphertext.size.toLong(), created.size)
            // The DTO's filename is opaque std-base64, not the plaintext name.
            assertFalse(created.encryptedFilenameB64.contains("veiled-photo"))

            // ---- list endpoint returns it
            val listed = api.listAttachments(item.id)
            assertTrue(listed.any { it.id == created.id })
            assertEquals(ciphertext.size.toLong(), listed.first { it.id == created.id }.size)

            // ---- download returns the ciphertext VERBATIM (server stores opaque bytes)
            val downloaded = api.downloadAttachment(item.id, created.id)
            assertArrayEquals(ciphertext, downloaded)

            // THE ACCEPTANCE: the stored/transferred file cannot be opened directly —
            // it does NOT start with the PNG magic and holds no plaintext bytes.
            assertFalse("downloaded ciphertext must not carry the PNG magic", downloaded.startsWith(pngSignature))
            val downloadedText = String(downloaded, Charsets.ISO_8859_1)
            assertFalse(downloadedText.contains("veiled-photo"))
            assertFalse(downloadedText.contains("密码"))

            // ...yet the client decrypts it back to the exact original image.
            val decrypted = PayloadCipher.decryptAttachment(downloaded, vaultKey)
            assertArrayEquals(plaintext, decrypted)
            assertTrue("decrypted bytes start with the PNG magic again", decrypted.startsWith(pngSignature))

            // ---- the encrypted filename decrypts back to the original name
            val decryptedName = PayloadCipher.decryptFilename(
                AuthHash.fromBase64(created.encryptedFilenameB64),
                vaultKey,
            )
            assertEquals(filename, decryptedName)

            // ---- item-scoped 404s: a foreign/unknown item hides the attachment
            try {
                api.downloadAttachment(item.id + 100000, created.id)
                throw AssertionError("download scoped to a foreign item must 404")
            } catch (expected: ApiError.NotFound) {
                // expected
            }
            try {
                api.uploadAttachment(
                    item.id + 100000,
                    "image/png",
                    encryptedFilenameB64Url,
                    ciphertext,
                )
                throw AssertionError("upload to a foreign item must 404")
            } catch (expected: ApiError.NotFound) {
                // expected
            }

            // ---- delete removes it; re-download 404s and the list empties
            api.deleteAttachment(item.id, created.id)
            try {
                api.downloadAttachment(item.id, created.id)
                throw AssertionError("download after delete must 404")
            } catch (expected: ApiError.NotFound) {
                // expected
            }
            assertTrue(api.listAttachments(item.id).isEmpty())

            // ---- cleanup: revoke the session
            repository.logout()
            assertTrue(storage.sessionToken.isEmpty())
        }
    }

    /** Small helper: does this byte array begin with [prefix]? */
    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i]) return false
        }
        return true
    }
}
