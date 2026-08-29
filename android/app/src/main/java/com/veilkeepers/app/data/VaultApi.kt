package com.veilkeepers.app.data

import com.veilkeepers.app.crypto.AuthHash
import com.veilkeepers.app.crypto.PayloadCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

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
