package com.veilkeepers.app.data

import com.veilkeepers.app.crypto.AuthHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Asserts the vault JSON payloads match the frozen contract
 * (docs/api/vault.md) field-for-field, pins the JSONObject.NULL encoding
 * chosen for Uncategorized, and mirrors the backend payload boundaries
 * (TestVaultItemPayloadBoundaries: exactly 1 MiB accepted, 1 MiB+1 rejected).
 */
class VaultApiEncodingTest {

    private val nameB64 = AuthHash.toBase64(ByteArray(24) { it.toByte() })
    private val payloadB64 = AuthHash.toBase64(ByteArray(64) { (it + 40).toByte() })

    // ---- request bodies ---------------------------------------------------

    @Test
    fun categoryBodyFieldNamesMatchContract() {
        val body = VaultPayloads.categoryBody(nameB64)
        val keys = mutableListOf<String>()
        body.keys().forEachRemaining { keys.add(it) }
        assertEquals(setOf("encrypted_name"), keys.toSet())
        assertEquals(1, keys.size)
        assertEquals(nameB64, body.getString("encrypted_name"))
    }

    @Test
    fun itemBodyFieldNamesMatchContract() {
        val body = VaultPayloads.itemBody(7L, payloadB64)
        val keys = mutableListOf<String>()
        body.keys().forEachRemaining { keys.add(it) }
        assertEquals(setOf("category_id", "encrypted_payload"), keys.toSet())
        assertEquals(2, keys.size)
        assertEquals(7L, body.getLong("category_id"))
        assertEquals(payloadB64, body.getString("encrypted_payload"))
    }

    @Test
    fun nullCategoryIdIsEncodedAsExplicitJsonNull() {
        // Pinned form: JSONObject.NULL — org.json's put(name, null) DROPS the
        // key, so Uncategorized must be sent as an explicit JSON null, not by
        // omitting the field (both are valid server-side; we pin this one).
        val body = VaultPayloads.itemBody(null, payloadB64)
        assertTrue("category_id key must be present", body.has("category_id"))
        assertTrue("category_id must be a JSON null", body.isNull("category_id"))
        assertTrue(
            "serialized body must carry \"category_id\":null",
            body.toString().contains("\"category_id\":null"),
        )
        assertEquals(payloadB64, body.getString("encrypted_payload"))
    }

    @Test
    fun maxSizedCategoryNameStillFitsThe4KiBBodyLimit() {
        // 255 decoded bytes → ≤340 base64 chars → body far below 4 KiB, so the
        // name bound always implies the category body bound (docs/api/vault.md).
        val maxNameB64 = AuthHash.toBase64(ByteArray(255) { 1 })
        val body = VaultPayloads.categoryBody(maxNameB64)
        assertTrue(body.toString().toByteArray(Charsets.UTF_8).size <= VaultPayloads.MAX_CATEGORY_BODY_BYTES)
        assertEquals(4096, VaultPayloads.MAX_CATEGORY_BODY_BYTES)
    }

    // ---- limit pre-checks (before any network call) -----------------------

    @Test
    fun nameBoundsPinThe255ByteBoundary() {
        VaultPayloads.categoryBody(AuthHash.toBase64(ByteArray(1))) // min accepted
        VaultPayloads.categoryBody(AuthHash.toBase64(ByteArray(255))) // max accepted
        assertRejected { VaultPayloads.categoryBody(AuthHash.toBase64(ByteArray(0))) }
        assertRejected { VaultPayloads.categoryBody(AuthHash.toBase64(ByteArray(256))) }
        assertRejected { VaultPayloads.categoryBody("!!!not-base64!!!") }
    }

    @Test
    fun payloadBoundsPinTheExactOneMebibyteBoundary() {
        // Mirrors backend TestVaultItemPayloadBoundaries: exactly 1 MiB
        // decoded is accepted; 1 MiB + 1 is rejected.
        val oneMib = 1024 * 1024
        assertEquals(oneMib, com.veilkeepers.app.crypto.PayloadCipher.MAX_PAYLOAD_BYTES)

        VaultPayloads.itemBody(null, AuthHash.toBase64(ByteArray(1))) // min accepted
        VaultPayloads.itemBody(null, AuthHash.toBase64(ByteArray(oneMib))) // exactly 1 MiB
        assertRejected { VaultPayloads.itemBody(null, AuthHash.toBase64(ByteArray(oneMib + 1))) }
        assertRejected { VaultPayloads.itemBody(null, AuthHash.toBase64(ByteArray(0))) }
        assertRejected { VaultPayloads.itemBody(null, "###") }
    }

    // ---- request paths ----------------------------------------------------

    @Test
    fun listItemsBuildsTheCategoryIdQueryParam() {
        // The only branch encoding the contract's ?category_id= filter:
        // a non-null category id becomes a query parameter...
        assertEquals(
            "/api/v1/vault/items?category_id=7",
            HttpVaultApi.itemsListPath(7L),
        )
        // ...and null (Uncategorized / full list) sends no query at all.
        assertEquals("/api/v1/vault/items", HttpVaultApi.itemsListPath(null))
    }

    // ---- response parsing -------------------------------------------------

    @Test
    fun parseCategoryMatchesContractFields() {
        val entry = VaultPayloads.parseCategory(
            org.json.JSONObject(
                """
                {
                  "id": 3,
                  "encrypted_name": "$nameB64",
                  "item_count": 5,
                  "created_at": "2026-01-01T00:00:00Z",
                  "updated_at": "2026-01-02T03:04:05Z"
                }
                """.trimIndent()
            )
        )
        assertEquals(3L, entry.id)
        assertEquals(nameB64, entry.encryptedNameB64)
        assertEquals(5, entry.itemCount)
        assertEquals("2026-01-01T00:00:00Z", entry.createdAt)
        assertEquals("2026-01-02T03:04:05Z", entry.updatedAt)
    }

    @Test
    fun parseItemMatchesContractFieldsWithCategoryId() {
        val entry = VaultPayloads.parseItem(
            org.json.JSONObject(
                """
                {
                  "id": 10,
                  "category_id": 1,
                  "encrypted_payload": "$payloadB64",
                  "created_at": "2026-01-01T00:00:00Z",
                  "updated_at": "2026-01-02T00:00:00Z"
                }
                """.trimIndent()
            )
        )
        assertEquals(10L, entry.id)
        assertEquals(1L, entry.categoryId)
        assertEquals(payloadB64, entry.encryptedPayloadB64)
        assertEquals("2026-01-01T00:00:00Z", entry.createdAt)
        assertEquals("2026-01-02T00:00:00Z", entry.updatedAt)
    }

    @Test
    fun parseItemTreatsNullAndMissingCategoryIdAsUncategorized() {
        val withNull = VaultPayloads.parseItem(
            org.json.JSONObject("""{"id": 11, "category_id": null, "encrypted_payload": "$payloadB64"}""")
        )
        assertNull(withNull.categoryId)

        // The contract carries JSON null; a hostile/old server omitting the
        // key entirely must also parse as Uncategorized, never crash.
        val missing = VaultPayloads.parseItem(
            org.json.JSONObject("""{"id": 12, "encrypted_payload": "$payloadB64"}""")
        )
        assertNull(missing.categoryId)
    }

    @Test
    fun parseCategoryListParsesEntriesAndHasMore() {
        val page = VaultPayloads.parseCategoryList(
            org.json.JSONObject(
                """
                {
                  "categories": [
                    {"id": 1, "encrypted_name": "$nameB64", "item_count": 0,
                     "created_at": "2026-01-01T00:00:00Z", "updated_at": "2026-01-01T00:00:00Z"},
                    {"id": 2, "encrypted_name": "$nameB64", "item_count": 3,
                     "created_at": "2026-01-01T00:00:00Z", "updated_at": "2026-01-03T00:00:00Z"}
                  ],
                  "has_more": true
                }
                """.trimIndent()
            )
        )
        assertEquals(listOf(1L, 2L), page.categories.map { it.id })
        assertTrue(page.hasMore)
    }

    @Test
    fun parseItemListParsesEntriesAndHasMoreDefaultsFalse() {
        val page = VaultPayloads.parseItemList(
            org.json.JSONObject(
                """
                {
                  "items": [
                    {"id": 10, "category_id": 1, "encrypted_payload": "$payloadB64",
                     "created_at": "2026-01-01T00:00:00Z", "updated_at": "2026-01-02T00:00:00Z"},
                    {"id": 11, "category_id": null, "encrypted_payload": "$payloadB64",
                     "created_at": "2026-01-01T00:00:00Z", "updated_at": "2026-01-01T00:00:00Z"}
                  ],
                  "has_more": false
                }
                """.trimIndent()
            )
        )
        assertEquals(listOf(10L, 11L), page.items.map { it.id })
        assertEquals(1L, page.items[0].categoryId)
        assertNull(page.items[1].categoryId)
        assertFalse(page.hasMore)

        // has_more absent → false.
        val noFlag = VaultPayloads.parseItemList(org.json.JSONObject("""{"items": []}"""))
        assertFalse(noFlag.hasMore)
        assertTrue(noFlag.items.isEmpty())
    }

    @Test
    fun statusOkEnvelopeIsParsedStrictly() {
        VaultPayloads.parseStatusOk(org.json.JSONObject("""{"status":"ok"}"""))
        assertRejected(statusOk = true) {
            VaultPayloads.parseStatusOk(org.json.JSONObject("""{"status":"weird"}"""))
        }
        assertRejected(statusOk = true) {
            VaultPayloads.parseStatusOk(org.json.JSONObject("""{}"""))
        }
    }

    @Test
    fun base64ShapeMatchesBackendStdEncoding() {
        val base64Shape = Regex("^[A-Za-z0-9+/]+={0,2}$")
        assertTrue(nameB64.matches(base64Shape))
        assertTrue(payloadB64.matches(base64Shape))
    }

    private fun assertRejected(statusOk: Boolean = false, block: () -> Unit) {
        try {
            block()
            fail("expected rejection")
        } catch (expected: IllegalArgumentException) {
            assertFalse(statusOk) // sanity: IllegalArgumentException is for body checks
        } catch (expected: ApiError.Internal) {
            assertTrue(statusOk)
        }
    }
}
