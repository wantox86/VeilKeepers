package com.veilkeepers.app.vault

import com.veilkeepers.app.auth.AuthRepository
import com.veilkeepers.app.crypto.AuthHash
import com.veilkeepers.app.crypto.PayloadCipher
import com.veilkeepers.app.data.ApiClient
import com.veilkeepers.app.data.AttachmentEntry
import com.veilkeepers.app.data.CategoryEntry
import com.veilkeepers.app.data.HttpVaultApi
import com.veilkeepers.app.data.ItemEntry
import com.veilkeepers.app.data.VaultApi
import com.veilkeepers.app.data.VaultPayloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Vault domain layer: fetches categories + items in parallel, decrypts and
 * parses every entry eagerly with the in-memory VK, and encrypts all writes
 * with [PayloadCipher] before they reach the wire.
 *
 * V0.1 memory-bound note: the list contract is capped at 500 items / 200
 * categories and each decrypted payload may be up to 1 MiB, so the worst case
 * for one eager decrypt pass is ~500 × 1 MiB held briefly on
 * [Dispatchers.Default]. Acceptable for a single-user homelab vault; a
 * streaming/page-cache design is deferred (no pagination mechanism exists in
 * the Sprint 4 contract anyway).
 *
 * The VK never leaves memory: no SessionStore keys, no File/Room writes, and
 * NOTHING in this package is ever logged — decrypt failures degrade to a
 * static generic marker, never exposing blob or exception detail.
 */
class VaultRepository(
    private val vaultKey: ByteArray,
    private val sessionToken: String,
    baseUrl: String,
    private val authRepository: AuthRepository,
    apiFactory: (baseUrl: String, bearerToken: String) -> VaultApi = { base, token ->
        HttpVaultApi(ApiClient(base), token)
    },
) {
    private val api = apiFactory(baseUrl, sessionToken)

    /** Parallel categories+items fetch, fully decrypted (see class note). */
    suspend fun refresh(): VaultSnapshot = coroutineScope {
        val categoriesDeferred = async { api.listCategories() }
        val itemsDeferred = async { api.listItems() }
        val categoriesPage = categoriesDeferred.await()
        val itemsPage = itemsDeferred.await()
        withContext(Dispatchers.Default) {
            VaultSnapshot(
                categories = categoriesPage.categories.map { decryptCategory(it) },
                items = DecryptedItemList(itemsPage.items.map { decryptItem(it) }, itemsPage.hasMore),
                hasMoreWarning = categoriesPage.hasMore || itemsPage.hasMore,
            )
        }
    }

    /** Encrypts [name] and POSTs it; returns the created category from the 201 DTO. */
    suspend fun createCategory(name: String): DecryptedCategory =
        decryptCategory(
            api.createCategory(AuthHash.toBase64(PayloadCipher.encryptName(name, vaultKey)))
        )

    /** Encrypts [name] and PUTs it (full replacement). */
    suspend fun renameCategory(id: Long, name: String) {
        api.updateCategory(id, AuthHash.toBase64(PayloadCipher.encryptName(name, vaultKey)))
    }

    /**
     * DELETEs the category (server moves its items to Uncategorized) and
     * refreshes the item list so the move is reflected locally.
     */
    suspend fun deleteCategory(id: Long): DecryptedItemList {
        api.deleteCategory(id)
        val page = api.listItems()
        return withContext(Dispatchers.Default) {
            DecryptedItemList(page.items.map { decryptItem(it) }, page.hasMore)
        }
    }

    /** Encrypts the payload and POSTs; the returned 201 DTO is used directly (no refetch). */
    suspend fun createItem(
        categoryId: Long?,
        title: String,
        notes: String,
        fields: List<VaultField>,
    ): DecryptedItem {
        val entry = api.createItem(categoryId, encryptPayload(title, notes, fields))
        return DecryptedItem(
            id = entry.id,
            categoryId = entry.categoryId,
            title = title,
            notes = notes,
            fields = fields,
            undecryptable = false,
            createdAt = entry.createdAt,
            updatedAt = entry.updatedAt,
        )
    }

    /**
     * PUTs the replacement and reconstructs the item LOCALLY from the inputs
     * plus the previous entry's timestamps — mirroring the createItem pattern.
     * A PUT→GET refresh could fail AFTER the server already committed, which
     * would falsely report "unchanged"; local reconstruction never lies about
     * success. The server bumped updated_at, but carrying the prior value
     * locally is acceptable and self-corrects on the next full refresh.
     */
    suspend fun updateItem(
        id: Long,
        categoryId: Long?,
        title: String,
        notes: String,
        fields: List<VaultField>,
        previous: DecryptedItem?,
    ): DecryptedItem {
        api.updateItem(id, categoryId, encryptPayload(title, notes, fields))
        return DecryptedItem(
            id = id,
            categoryId = categoryId,
            title = title,
            notes = notes,
            fields = fields,
            undecryptable = false,
            createdAt = previous?.createdAt.orEmpty(),
            updatedAt = previous?.updatedAt.orEmpty(),
        )
    }

    suspend fun deleteItem(id: Long) {
        api.deleteItem(id)
    }

    /**
     * Fetches an item's attachments and decrypts each filename with the
     * in-memory VK. A filename that fails GCM auth degrades to
     * [UNDECRYPTABLE] (never crashes, never surfaces the blob).
     */
    suspend fun listAttachments(itemId: Long): List<AttachmentMeta> {
        val entries = api.listAttachments(itemId)
        return withContext(Dispatchers.Default) { entries.map { decryptAttachmentMeta(it) } }
    }

    /**
     * Encrypts the attachment [plaintext] and its [filename] under the VK and
     * uploads them to [itemId], returning the decrypted view of the 201 DTO.
     * The MIME whitelist and the ciphertext/filename size bounds are enforced
     * in the API layer BEFORE any bytes reach the wire.
     */
    suspend fun uploadAttachment(
        itemId: Long,
        filename: String,
        mimeType: String,
        plaintext: ByteArray,
    ): AttachmentMeta {
        val ciphertext = withContext(Dispatchers.Default) {
            PayloadCipher.encryptAttachment(plaintext, vaultKey)
        }
        val encryptedFilenameB64Url = withContext(Dispatchers.Default) {
            VaultPayloads.toBase64Url(PayloadCipher.encryptFilename(filename, vaultKey))
        }
        val entry = api.uploadAttachment(itemId, mimeType, encryptedFilenameB64Url, ciphertext)
        return withContext(Dispatchers.Default) { decryptAttachmentMeta(entry) }
    }

    /** Downloads an attachment's ciphertext and decrypts it to plaintext bytes. */
    suspend fun downloadAttachment(itemId: Long, attachmentId: Long): ByteArray {
        val ciphertext = api.downloadAttachment(itemId, attachmentId)
        return withContext(Dispatchers.Default) {
            PayloadCipher.decryptAttachment(ciphertext, vaultKey)
        }
    }

    /** DELETEs an attachment; the server also removes its ciphertext file. */
    suspend fun deleteAttachment(itemId: Long, attachmentId: Long) {
        api.deleteAttachment(itemId, attachmentId)
    }

    /**
     * Zeroizes the in-memory VK. Idempotent. Called on EVERY terminal path
     * (lock & sign out, auto-lock, AND session-expired) so the plaintext VK
     * never outlives the session, regardless of how the vault was exited.
     */
    fun zeroizeVaultKey() {
        vaultKey.fill(0)
    }

    /**
     * Soft auto-lock (Sprint 6, spec.md §24): zeroizes the VK ONLY — the
     * server session stays alive and unlock (password or biometric) brings
     * the vault straight back. Only [lockAndLogout] revokes the session.
     */
    fun lock() {
        zeroizeVaultKey()
    }

    /**
     * Lock & sign out: zeroizes the VK in-place FIRST, then delegates the
     * session revocation/store wipe to [AuthRepository].
     */
    suspend fun lockAndLogout() {
        zeroizeVaultKey()
        authRepository.logout()
    }

    private fun encryptPayload(title: String, notes: String, fields: List<VaultField>): String =
        AuthHash.toBase64(
            PayloadCipher.encryptPayload(ItemPayload.encode(title, notes, fields), vaultKey)
        )

    private fun decryptCategory(entry: CategoryEntry): DecryptedCategory = try {
        DecryptedCategory(
            id = entry.id,
            name = PayloadCipher.decryptToString(
                AuthHash.fromBase64(entry.encryptedNameB64),
                vaultKey,
            ),
            itemCount = entry.itemCount,
            createdAt = entry.createdAt,
            updatedAt = entry.updatedAt,
        )
    } catch (e: Exception) {
        // Never crash, never log, never expose the blob: static marker only.
        DecryptedCategory(entry.id, UNDECRYPTABLE, entry.itemCount, entry.createdAt, entry.updatedAt)
    }

    private fun decryptItem(entry: ItemEntry): DecryptedItem {
        val content = try {
            ItemPayload.parse(
                PayloadCipher.decryptToString(
                    AuthHash.fromBase64(entry.encryptedPayloadB64),
                    vaultKey,
                )
            )
        } catch (e: Exception) {
            // Never crash, never log, never expose the blob: static marker only.
            null
        }
        return if (content != null) {
            DecryptedItem(
                id = entry.id,
                categoryId = entry.categoryId,
                title = content.title,
                notes = content.notes,
                fields = content.fields,
                undecryptable = false,
                createdAt = entry.createdAt,
                updatedAt = entry.updatedAt,
            )
        } else {
            DecryptedItem(
                id = entry.id,
                categoryId = entry.categoryId,
                title = UNDECRYPTABLE,
                notes = "",
                fields = emptyList(),
                undecryptable = true,
                createdAt = entry.createdAt,
                updatedAt = entry.updatedAt,
            )
        }
    }

    private fun decryptAttachmentMeta(entry: AttachmentEntry): AttachmentMeta {
        val filename = try {
            PayloadCipher.decryptFilename(
                AuthHash.fromBase64(entry.encryptedFilenameB64),
                vaultKey,
            )
        } catch (e: Exception) {
            // Never crash, never log, never expose the blob: static marker only.
            UNDECRYPTABLE
        }
        return AttachmentMeta(
            id = entry.id,
            itemId = entry.vaultItemId,
            filename = filename,
            mimeType = entry.mimeType,
            size = entry.size,
            createdAt = entry.createdAt,
        )
    }

    companion object {
        /** Static generic marker for blobs that fail GCM auth or parsing. */
        const val UNDECRYPTABLE = "(cannot decrypt)"
    }
}
