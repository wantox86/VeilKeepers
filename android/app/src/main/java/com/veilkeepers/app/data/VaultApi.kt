package com.veilkeepers.app.data

import com.veilkeepers.app.crypto.AuthHash
import com.veilkeepers.app.crypto.PayloadCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64

/** Wire DTO: one category entry exactly as the backend returns it. */
data class CategoryEntry(
    val id: Long,
    /** Opaque client-encrypted blob, standard base64, stored verbatim. */
    val encryptedNameB64: String,
    val itemCount: Int,
    val createdAt: String,
    val updatedAt: String,
)

/** Wire DTO: one vault item exactly as the backend returns it. */
data class ItemEntry(
    val id: Long,
    /** null = Uncategorized. */
    val categoryId: Long?,
    /** Opaque client-encrypted blob, standard base64, stored verbatim. */
    val encryptedPayloadB64: String,
    val createdAt: String,
    val updatedAt: String,
)

/**
 * Wire DTO: one attachment exactly as the backend returns it
 * (docs/api/vault.md §Attachments). [encryptedFilenameB64] is the opaque
 * client-encrypted filename in standard base64 (the upload query form is
 * base64url; the DTO form is standard). [size] is the CIPHERTEXT byte length
 * stored on the server, so the plaintext length is size −
 * [com.veilkeepers.app.crypto.PayloadCipher.CIPHER_OVERHEAD_BYTES].
 */
data class AttachmentEntry(
    val id: Long,
    val vaultItemId: Long,
    val encryptedFilenameB64: String,
    val mimeType: String,
    val size: Long,
    val createdAt: String,
)

/** GET /api/v1/categories page (≤200) + warning-only overflow flag. */
data class CategoryListResult(val categories: List<CategoryEntry>, val hasMore: Boolean)

/** GET /api/v1/vault/items page (≤500) + warning-only overflow flag. */
data class ItemListResult(val items: List<ItemEntry>, val hasMore: Boolean)

/**
 * Vault surface of the frozen backend contract (docs/api/vault.md). An
 * interface so unit tests can inject fakes; [HttpVaultApi] is the real
 * implementation over [ApiClient]. Every call carries the session bearer
 * token supplied at construction.
 */
interface VaultApi {
    /** GET /api/v1/categories. */
    suspend fun listCategories(): CategoryListResult

    /** POST /api/v1/categories → 201 with the created category DTO. */
    suspend fun createCategory(encryptedNameB64: String): CategoryEntry

    /** PUT /api/v1/categories/{id} (full replacement) → {"status":"ok"}. */
    suspend fun updateCategory(id: Long, encryptedNameB64: String)

    /** DELETE /api/v1/categories/{id}; items move to Uncategorized. */
    suspend fun deleteCategory(id: Long)

    /** GET /api/v1/vault/items[?category_id=]. */
    suspend fun listItems(categoryId: Long? = null): ItemListResult

    /** POST /api/v1/vault/items → 201 with the created item DTO. */
    suspend fun createItem(categoryId: Long?, encryptedPayloadB64: String): ItemEntry

    /** GET /api/v1/vault/items/{id}. */
    suspend fun getItem(id: Long): ItemEntry

    /** PUT /api/v1/vault/items/{id} (full replacement) → {"status":"ok"}. */
    suspend fun updateItem(id: Long, categoryId: Long?, encryptedPayloadB64: String)

    /** DELETE /api/v1/vault/items/{id} → {"status":"ok"}. */
    suspend fun deleteItem(id: Long)

    /** GET /api/v1/vault/items/{id}/attachments → the item's attachments. */
    suspend fun listAttachments(itemId: Long): List<AttachmentEntry>

    /**
     * POST /api/v1/vault/items/{id}/attachments with the raw [ciphertext] as
     * the octet-stream body; [mimeType] and the base64url [encryptedFilenameB64Url]
     * ride in the query string → 201 with the created attachment DTO.
     */
    suspend fun uploadAttachment(
        itemId: Long,
        mimeType: String,
        encryptedFilenameB64Url: String,
        ciphertext: ByteArray,
    ): AttachmentEntry

    /**
     * GET /api/v1/vault/items/{id}/attachments/{attachmentId} → the raw
     * ciphertext bytes for client-side decryption.
     */
    suspend fun downloadAttachment(itemId: Long, attachmentId: Long): ByteArray

    /** DELETE /api/v1/vault/items/{id}/attachments/{attachmentId} → {"status":"ok"}. */
    suspend fun deleteAttachment(itemId: Long, attachmentId: Long)
}

/**
 * JSON payload builders/parsers for the vault endpoints. Field names must
 * match docs/api/vault.md byte-for-byte; covered by VaultApiEncodingTest.
 *
 * All client-side limit pre-checks live here so display-ready errors are
 * thrown BEFORE any network call, instead of letting the server 400.
 */
object VaultPayloads {
    /** Category create/update request body ceiling (backend maxCategoryBodyBytes). */
    const val MAX_CATEGORY_BODY_BYTES = 4096

    /**
     * Attachment MIME whitelist (spec-1.md §B.6), mirroring the backend's
     * allowedAttachmentMIMEs. Enforced client-side BEFORE upload so an
     * unsupported type fails with a display-ready error, not a server 400.
     */
    val ALLOWED_ATTACHMENT_MIMES = setOf("image/jpeg", "image/png", "image/webp", "image/gif")

    /**
     * Decoded encrypted-filename bounds, mirroring the backend's
     * minEncryptedFilenameBytes (overhead + 1) and the VARBINARY(255) column.
     */
    const val MIN_ENCRYPTED_FILENAME_BYTES = 29
    const val MAX_ENCRYPTED_FILENAME_BYTES = 255

    /** POST/PUT category body: {"encrypted_name": base64}. */
    fun categoryBody(encryptedNameB64: String): JSONObject {
        requireDecodedBounds(
            encryptedNameB64,
            PayloadCipher.MAX_NAME_BYTES,
            "Category name",
        )
        val body = JSONObject().put("encrypted_name", encryptedNameB64)
        // Defensive: with the 255-byte name bound a valid body always fits in
        // 4 KiB; keep the check in case the bound ever grows.
        if (body.toString().toByteArray(Charsets.UTF_8).size > MAX_CATEGORY_BODY_BYTES) {
            throw IllegalArgumentException(
                "Category request exceeds the $MAX_CATEGORY_BODY_BYTES-byte limit."
            )
        }
        return body
    }

    /** POST/PUT item body: {"category_id": number|null, "encrypted_payload": base64}. */
    fun itemBody(categoryId: Long?, encryptedPayloadB64: String): JSONObject {
        requireDecodedBounds(
            encryptedPayloadB64,
            PayloadCipher.MAX_PAYLOAD_BYTES,
            "Item content",
        )
        // JSONObject.put(name, null) DROPS the key, so Uncategorized must be
        // encoded explicitly as JSONObject.NULL → "category_id": null.
        // (Both omitting and null are valid per the contract; VaultApiEncodingTest
        // pins THIS chosen form.)
        return JSONObject()
            .put("category_id", categoryId ?: JSONObject.NULL)
            .put("encrypted_payload", encryptedPayloadB64)
    }

    fun parseCategory(json: JSONObject): CategoryEntry = CategoryEntry(
        id = json.getLong("id"),
        encryptedNameB64 = json.optString("encrypted_name", ""),
        itemCount = json.optInt("item_count", 0),
        createdAt = json.optString("created_at", ""),
        updatedAt = json.optString("updated_at", ""),
    )

    /** Missing OR null `category_id` both parse as Uncategorized (null). */
    fun parseItem(json: JSONObject): ItemEntry = ItemEntry(
        id = json.getLong("id"),
        categoryId = if (json.isNull("category_id")) null else json.getLong("category_id"),
        encryptedPayloadB64 = json.optString("encrypted_payload", ""),
        createdAt = json.optString("created_at", ""),
        updatedAt = json.optString("updated_at", ""),
    )

    fun parseCategoryList(json: JSONObject): CategoryListResult {
        val arr = json.optJSONArray("categories") ?: throw ApiError.Internal
        val categories = (0 until arr.length()).map { parseCategory(arr.getJSONObject(it)) }
        return CategoryListResult(categories, json.optBoolean("has_more", false))
    }

    fun parseItemList(json: JSONObject): ItemListResult {
        val arr = json.optJSONArray("items") ?: throw ApiError.Internal
        val items = (0 until arr.length()).map { parseItem(arr.getJSONObject(it)) }
        return ItemListResult(items, json.optBoolean("has_more", false))
    }

    /** PUT/DELETE answers exactly {"status":"ok"}; anything else is broken. */
    fun parseStatusOk(json: JSONObject) {
        if (json.optString("status", "") != "ok") throw ApiError.Internal
    }

    /** base64url without padding — the upload query-param alphabet (Go RawURLEncoding). */
    fun toBase64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Decodes base64url (padding optional); throws [IllegalArgumentException] if invalid. */
    fun fromBase64Url(encoded: String): ByteArray = Base64.getUrlDecoder().decode(encoded)

    /**
     * POST /api/v1/vault/items/{id}/attachments request path. [mimeType] and
     * the base64url [encryptedFilenameB64Url] are percent-encoded into the
     * query string (the mime's `/` MUST be escaped). Extracted so the encoding
     * is testable without a live connection.
     */
    fun attachmentUploadPath(itemId: Long, mimeType: String, encryptedFilenameB64Url: String): String {
        val mime = URLEncoder.encode(mimeType, "UTF-8")
        val filename = URLEncoder.encode(encryptedFilenameB64Url, "UTF-8")
        return "/api/v1/vault/items/$itemId/attachments?mime_type=$mime&encrypted_filename=$filename"
    }

    /**
     * Client-side attachment pre-checks, run BEFORE any upload so limits fail
     * with a display-ready error instead of a server 400 (spec-1.md §B.6,
     * two-sided enforcement). Validates the MIME whitelist, the ciphertext
     * size (≤ [PayloadCipher.MAX_ATTACHMENT_BYTES]), and the base64url
     * encrypted filename's decoded length (29..255 bytes).
     */
    fun requireAttachmentBounds(
        mimeType: String,
        ciphertext: ByteArray,
        encryptedFilenameB64Url: String,
    ) {
        if (mimeType !in ALLOWED_ATTACHMENT_MIMES) {
            throw IllegalArgumentException("Unsupported attachment type: only images are allowed.")
        }
        if (ciphertext.isEmpty()) {
            throw IllegalArgumentException("Attachment must not be empty.")
        }
        if (ciphertext.size > PayloadCipher.MAX_ATTACHMENT_BYTES) {
            throw IllegalArgumentException(
                "Attachment exceeds the ${PayloadCipher.MAX_ATTACHMENT_BYTES}-byte limit."
            )
        }
        val decoded = try {
            fromBase64Url(encryptedFilenameB64Url)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Attachment filename is not valid base64url.")
        }
        if (decoded.size < MIN_ENCRYPTED_FILENAME_BYTES || decoded.size > MAX_ENCRYPTED_FILENAME_BYTES) {
            throw IllegalArgumentException(
                "Encrypted attachment filename must be " +
                    "$MIN_ENCRYPTED_FILENAME_BYTES..$MAX_ENCRYPTED_FILENAME_BYTES bytes."
            )
        }
    }

    fun parseAttachment(json: JSONObject): AttachmentEntry = AttachmentEntry(
        id = json.getLong("id"),
        vaultItemId = json.getLong("vault_item_id"),
        encryptedFilenameB64 = json.optString("encrypted_filename", ""),
        mimeType = json.optString("mime_type", ""),
        size = json.optLong("size", 0),
        createdAt = json.optString("created_at", ""),
    )

    fun parseAttachmentList(json: JSONObject): List<AttachmentEntry> {
        val arr = json.optJSONArray("attachments") ?: throw ApiError.Internal
        return (0 until arr.length()).map { parseAttachment(arr.getJSONObject(it)) }
    }

    private fun requireDecodedBounds(b64: String, maxBytes: Int, what: String) {
        val decoded = try {
            AuthHash.fromBase64(b64)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("$what is not valid base64.")
        }
        if (decoded.isEmpty()) {
            throw IllegalArgumentException("$what must not be empty.")
        }
        if (decoded.size > maxBytes) {
            throw IllegalArgumentException("$what exceeds the $maxBytes-byte limit.")
        }
    }
}

/** [VaultApi] implementation backed by [ApiClient] (blocking I/O on Dispatchers.IO). */
class HttpVaultApi(private val client: ApiClient, private val bearerToken: String) : VaultApi {

    override suspend fun listCategories(): CategoryListResult = withContext(Dispatchers.IO) {
        VaultPayloads.parseCategoryList(client.getJson("/api/v1/categories", bearerToken))
    }

    override suspend fun createCategory(encryptedNameB64: String): CategoryEntry =
        withContext(Dispatchers.IO) {
            VaultPayloads.parseCategory(
                client.postJson(
                    "/api/v1/categories",
                    VaultPayloads.categoryBody(encryptedNameB64),
                    bearerToken,
                )
            )
        }

    override suspend fun updateCategory(id: Long, encryptedNameB64: String) {
        withContext(Dispatchers.IO) {
            VaultPayloads.parseStatusOk(
                client.putJson(
                    "/api/v1/categories/$id",
                    VaultPayloads.categoryBody(encryptedNameB64),
                    bearerToken,
                )
            )
        }
    }

    override suspend fun deleteCategory(id: Long) {
        withContext(Dispatchers.IO) {
            VaultPayloads.parseStatusOk(client.deleteJson("/api/v1/categories/$id", bearerToken))
        }
    }

    override suspend fun listItems(categoryId: Long?): ItemListResult =
        withContext(Dispatchers.IO) {
            VaultPayloads.parseItemList(client.getJson(itemsListPath(categoryId), bearerToken))
        }

    override suspend fun createItem(categoryId: Long?, encryptedPayloadB64: String): ItemEntry =
        withContext(Dispatchers.IO) {
            VaultPayloads.parseItem(
                client.postJson(
                    "/api/v1/vault/items",
                    VaultPayloads.itemBody(categoryId, encryptedPayloadB64),
                    bearerToken,
                )
            )
        }

    override suspend fun getItem(id: Long): ItemEntry = withContext(Dispatchers.IO) {
        VaultPayloads.parseItem(client.getJson("/api/v1/vault/items/$id", bearerToken))
    }

    override suspend fun updateItem(id: Long, categoryId: Long?, encryptedPayloadB64: String) {
        withContext(Dispatchers.IO) {
            VaultPayloads.parseStatusOk(
                client.putJson(
                    "/api/v1/vault/items/$id",
                    VaultPayloads.itemBody(categoryId, encryptedPayloadB64),
                    bearerToken,
                )
            )
        }
    }

    override suspend fun deleteItem(id: Long) {
        withContext(Dispatchers.IO) {
            VaultPayloads.parseStatusOk(client.deleteJson("/api/v1/vault/items/$id", bearerToken))
        }
    }

    override suspend fun listAttachments(itemId: Long): List<AttachmentEntry> =
        withContext(Dispatchers.IO) {
            VaultPayloads.parseAttachmentList(
                client.getJson("/api/v1/vault/items/$itemId/attachments", bearerToken)
            )
        }

    override suspend fun uploadAttachment(
        itemId: Long,
        mimeType: String,
        encryptedFilenameB64Url: String,
        ciphertext: ByteArray,
    ): AttachmentEntry = withContext(Dispatchers.IO) {
        // Pre-check limits before the body is written, so an oversized or
        // non-whitelisted upload fails locally with a display-ready error.
        VaultPayloads.requireAttachmentBounds(mimeType, ciphertext, encryptedFilenameB64Url)
        VaultPayloads.parseAttachment(
            client.postBinary(
                VaultPayloads.attachmentUploadPath(itemId, mimeType, encryptedFilenameB64Url),
                ciphertext,
                bearerToken,
            )
        )
    }

    override suspend fun downloadAttachment(itemId: Long, attachmentId: Long): ByteArray =
        withContext(Dispatchers.IO) {
            client.getBinary(
                "/api/v1/vault/items/$itemId/attachments/$attachmentId",
                bearerToken,
            )
        }

    override suspend fun deleteAttachment(itemId: Long, attachmentId: Long) {
        withContext(Dispatchers.IO) {
            VaultPayloads.parseStatusOk(
                client.deleteJson(
                    "/api/v1/vault/items/$itemId/attachments/$attachmentId",
                    bearerToken,
                )
            )
        }
    }

    companion object {
        /**
         * GET /api/v1/vault/items request path; a non-null [categoryId]
         * becomes the contract's `?category_id=` query parameter. Extracted
         * so the encoding is testable without a live HTTP connection.
         */
        fun itemsListPath(categoryId: Long?): String =
            if (categoryId != null) {
                "/api/v1/vault/items?category_id=$categoryId"
            } else {
                "/api/v1/vault/items"
            }
    }
}
