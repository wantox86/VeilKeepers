package com.veilkeepers.app.vault.attach

import com.veilkeepers.app.auth.AuthRepository
import com.veilkeepers.app.crypto.AuthHash
import com.veilkeepers.app.crypto.KdfParams
import com.veilkeepers.app.crypto.PayloadCipher
import com.veilkeepers.app.crypto.VaultKey
import com.veilkeepers.app.data.ApiError
import com.veilkeepers.app.data.AttachmentEntry
import com.veilkeepers.app.data.AuthApi
import com.veilkeepers.app.data.CategoryEntry
import com.veilkeepers.app.data.CategoryListResult
import com.veilkeepers.app.data.ItemEntry
import com.veilkeepers.app.data.ItemListResult
import com.veilkeepers.app.data.KdfInfo
import com.veilkeepers.app.data.LoginResult
import com.veilkeepers.app.data.SessionStorage
import com.veilkeepers.app.data.VaultApi
import com.veilkeepers.app.data.VaultPayloads
import com.veilkeepers.app.vault.VaultRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

private val ATTACH_TEST_PARAMS = KdfParams(m = 1024, t = 1, p = 1)

/**
 * In-memory [VaultApi] for the attachment flows: stores items by id and
 * attachments as OPAQUE ciphertext + a std-base64 encrypted filename, exactly
 * like the backend DTO. Mirrors the FK ON DELETE CASCADE (deleting an item
 * drops its attachments) and the ownership-hiding 404s.
 */
private class FakeAttachmentApi : VaultApi {

    class StoredFile(
        val itemId: Long,
        val encryptedFilenameB64: String,
        val mimeType: String,
        val ciphertext: ByteArray,
        val createdAt: String,
    )

    private val items = LinkedHashSet<Long>()
    private val files = LinkedHashMap<Long, StoredFile>()
    private var nextFileId = 1L
    private var tick = 0

    fun seedItem(id: Long) {
        items.add(id)
    }

    /** Injects an attachment whose filename blob will NOT decrypt under the VK. */
    fun seedUndecryptableFile(itemId: Long) {
        val id = nextFileId++
        files[id] = StoredFile(
            itemId = itemId,
            encryptedFilenameB64 = AuthHash.toBase64("garbage-not-a-real-blob".toByteArray()),
            mimeType = "image/png",
            ciphertext = ByteArray(64) { it.toByte() },
            createdAt = timestamp(),
        )
    }

    val storedFiles: Map<Long, StoredFile> get() = files

    override suspend fun listAttachments(itemId: Long): List<AttachmentEntry> {
        if (itemId !in items) throw ApiError.NotFound
        return files.entries
            .filter { it.value.itemId == itemId }
            .sortedByDescending { it.value.createdAt }
            .map { (id, f) ->
                AttachmentEntry(id, itemId, f.encryptedFilenameB64, f.mimeType, f.ciphertext.size.toLong(), f.createdAt)
            }
    }

    override suspend fun uploadAttachment(
        itemId: Long,
        mimeType: String,
        encryptedFilenameB64Url: String,
        ciphertext: ByteArray,
    ): AttachmentEntry {
        if (itemId !in items) throw ApiError.NotFound
        VaultPayloads.requireAttachmentBounds(mimeType, ciphertext, encryptedFilenameB64Url)
        val id = nextFileId++
        val now = timestamp()
        val filenameB64 = AuthHash.toBase64(VaultPayloads.fromBase64Url(encryptedFilenameB64Url))
        files[id] = StoredFile(itemId, filenameB64, mimeType, ciphertext, now)
        return AttachmentEntry(id, itemId, filenameB64, mimeType, ciphertext.size.toLong(), now)
    }

    override suspend fun downloadAttachment(itemId: Long, attachmentId: Long): ByteArray {
        val f = files[attachmentId] ?: throw ApiError.NotFound
        if (f.itemId != itemId) throw ApiError.NotFound
        return f.ciphertext
    }

    override suspend fun deleteAttachment(itemId: Long, attachmentId: Long) {
        val f = files[attachmentId] ?: throw ApiError.NotFound
        if (f.itemId != itemId) throw ApiError.NotFound
        files.remove(attachmentId)
    }

    override suspend fun deleteItem(id: Long) {
        if (!items.remove(id)) throw ApiError.NotFound
        files.entries.filter { it.value.itemId == id }.map { it.key }.forEach { files.remove(it) }
    }

    override suspend fun listCategories(): CategoryListResult = throw UnsupportedOperationException()
    override suspend fun createCategory(encryptedNameB64: String): CategoryEntry = throw UnsupportedOperationException()
    override suspend fun updateCategory(id: Long, encryptedNameB64: String) = throw UnsupportedOperationException()
    override suspend fun deleteCategory(id: Long) = throw UnsupportedOperationException()
    override suspend fun listItems(categoryId: Long?): ItemListResult = throw UnsupportedOperationException()
    override suspend fun createItem(categoryId: Long?, encryptedPayloadB64: String): ItemEntry = throw UnsupportedOperationException()
    override suspend fun getItem(id: Long): ItemEntry = throw UnsupportedOperationException()
    override suspend fun updateItem(id: Long, categoryId: Long?, encryptedPayloadB64: String) = throw UnsupportedOperationException()

    private fun timestamp(): String {
        tick++
        return "2026-09-02T%02d:%02d:%02dZ".format((tick / 3600) % 24, (tick / 60) % 60, tick % 60)
    }
}

/** [AuthApi] stub — the attachment flows never authenticate. */
private class AttachmentAuthApi : AuthApi {
    override suspend fun getKdf(username: String): KdfInfo = throw ApiError.NotFound
    override suspend fun register(
        username: String,
        authHashB64: String,
        kdfSaltB64: String,
        kdfParams: KdfParams,
        wrappedVaultKeyB64: String,
    ) = Unit

    override suspend fun login(
        username: String,
        authHashB64: String,
        deviceIdentifier: String,
        deviceName: String,
    ): LoginResult = throw ApiError.Internal

    override suspend fun logout(bearerToken: String) = Unit
}

/** In-memory [SessionStorage] for JVM tests (no Android Keystore). */
private class AttachmentInMemoryStorage : SessionStorage {
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
    override fun deviceName(): String = "AttachmentTestDevice"
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
 * Attachment flow tests over [VaultRepository] with a real [PayloadCipher] and
 * an in-memory backend fake. The AttachmentViewModel is a thin coroutine
 * wrapper over these same repository calls (viewModelScope needs the Android
 * main thread), so the repository is the unit under test.
 */
class AttachmentFlowsTest {

    private val vk = VaultKey.generate()
    private val storage = AttachmentInMemoryStorage().apply {
        sessionToken = "test-token"
        serverUrl = "http://vault.test"
    }
    private val api = FakeAttachmentApi()
    private val repository = VaultRepository(
        vaultKey = vk,
        sessionToken = "test-token",
        baseUrl = "http://vault.test",
        authRepository = AuthRepository(
            storage,
            ATTACH_TEST_PARAMS,
            vaultApiFactory = { _, _ -> api },
            apiFactory = { AttachmentAuthApi() },
        ),
        apiFactory = { _, _ -> api },
    )

    @Test
    fun uploadStoresCiphertextOnlyAndDownloadDecryptsByteForByte() = runBlocking {
        api.seedItem(1L)
        val plaintext = ByteArray(2048) { (it * 31 % 256).toByte() }
        val filename = "beach-photo 密码.png"

        val meta = repository.uploadAttachment(1L, filename, "image/png", plaintext)

        // The decrypted view round-trips the filename and reports the CIPHERTEXT size.
        assertEquals(filename, meta.filename)
        assertEquals("image/png", meta.mimeType)
        assertEquals(1L, meta.itemId)
        assertEquals(plaintext.size.toLong() + PayloadCipher.CIPHER_OVERHEAD_BYTES, meta.size)

        // The "server" holds only opaque bytes: no plaintext, no readable filename.
        val stored = api.storedFiles.values.single()
        val storedText = String(stored.ciphertext, Charsets.ISO_8859_1)
        assertFalse(storedText.contains("beach-photo"))
        assertFalse(storedText.contains("密码"))
        assertFalse(String(stored.encryptedFilenameB64.toByteArray(), Charsets.ISO_8859_1).contains("beach"))

        // list → the attachment appears with its decrypted filename.
        val listed = repository.listAttachments(1L)
        assertEquals(listOf(meta.id), listed.map { it.id })
        assertEquals(filename, listed.single().filename)

        // download → decrypt equals the original plaintext exactly.
        val downloaded = repository.downloadAttachment(1L, meta.id)
        assertArrayEquals(plaintext, downloaded)
    }

    @Test
    fun deleteRemovesTheAttachmentAndFurtherDownloadIsNotFound() = runBlocking {
        api.seedItem(2L)
        val meta = repository.uploadAttachment(2L, "note.jpg", "image/jpeg", ByteArray(128) { it.toByte() })

        repository.deleteAttachment(2L, meta.id)
        assertTrue(repository.listAttachments(2L).isEmpty())

        try {
            repository.downloadAttachment(2L, meta.id)
            fail("download after delete must 404")
        } catch (expected: ApiError.NotFound) {
            // expected
        }
    }

    @Test
    fun cascadeOnItemDeleteDropsAttachments() = runBlocking {
        api.seedItem(3L)
        repository.uploadAttachment(3L, "a.png", "image/png", ByteArray(64) { 1 })
        repository.uploadAttachment(3L, "b.png", "image/png", ByteArray(64) { 2 })
        assertEquals(2, repository.listAttachments(3L).size)

        // Backend FK ON DELETE CASCADE, mirrored by the fake.
        api.deleteItem(3L)
        assertTrue(api.storedFiles.isEmpty())
    }

    @Test
    fun undecryptableFilenameShowsStaticMarkerAndNeverCrashes() = runBlocking {
        api.seedItem(4L)
        api.seedUndecryptableFile(4L)

        val listed = repository.listAttachments(4L)
        assertEquals(1, listed.size)
        assertEquals(VaultRepository.UNDECRYPTABLE, listed.single().filename)
        // Metadata still surfaces even though the filename blob failed GCM auth.
        assertEquals("image/png", listed.single().mimeType)
    }

    @Test
    fun listingAnUnknownItemIsNotFound() = runBlocking {
        try {
            repository.listAttachments(9999L)
            fail("listing an unknown item's attachments must 404")
        } catch (expected: ApiError.NotFound) {
            // expected
        }
    }
}
