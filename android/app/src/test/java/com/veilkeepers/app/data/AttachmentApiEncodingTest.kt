package com.veilkeepers.app.data

import com.veilkeepers.app.crypto.PayloadCipher
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Sprint 8 attachment wire-encoding tests: the base64url (no-padding) query
 * alphabet, the percent-encoded upload path, the two-sided client pre-checks
 * ([VaultPayloads.requireAttachmentBounds]), and DTO parsing — all matched to
 * the frozen contract (docs/api/vault.md §Attachments) and spec-1.md §B.6.
 */
class AttachmentApiEncodingTest {

    @Test
    fun base64UrlIsUnpaddedAndRoundTrips() {
        // Lengths 1..3 mod 3 exercise the padding that MUST be stripped.
        for (len in listOf(1, 2, 3, 29, 255)) {
            val bytes = ByteArray(len) { (it * 13 % 256).toByte() }
            val encoded = VaultPayloads.toBase64Url(bytes)
            assertFalse("base64url must carry no '=' padding", encoded.contains("="))
            assertFalse("base64url must not use the '+' char", encoded.contains("+"))
            assertFalse("base64url must not use the '/' char", encoded.contains("/"))
            assertTrue(bytes.contentEquals(VaultPayloads.fromBase64Url(encoded)))
        }
    }

    @Test
    fun uploadPathPercentEncodesMimeAndCarriesFilename() {
        val filename = VaultPayloads.toBase64Url(ByteArray(29) { it.toByte() })
        val path = VaultPayloads.attachmentUploadPath(42L, "image/png", filename)
        assertEquals(
            "/api/v1/vault/items/42/attachments?mime_type=image%2Fpng&encrypted_filename=$filename",
            path,
        )
        // The mime's '/' MUST be escaped so it cannot break the query string.
        assertTrue(path.contains("mime_type=image%2Fpng"))
    }

    @Test
    fun boundsAcceptAValidImageAttachment() {
        val filename = VaultPayloads.toBase64Url(ByteArray(29) { it.toByte() })
        // No throw for each whitelisted MIME at the minimum legal sizes.
        VaultPayloads.ALLOWED_ATTACHMENT_MIMES.forEach { mime ->
            VaultPayloads.requireAttachmentBounds(mime, ByteArray(64) { 1 }, filename)
        }
        assertEquals(
            setOf("image/jpeg", "image/png", "image/webp", "image/gif"),
            VaultPayloads.ALLOWED_ATTACHMENT_MIMES,
        )
    }

    @Test
    fun boundsRejectNonWhitelistedMime() {
        val filename = VaultPayloads.toBase64Url(ByteArray(29) { it.toByte() })
        for (mime in listOf("application/pdf", "image/svg+xml", "text/plain", "IMAGE/PNG", "")) {
            assertRejected { VaultPayloads.requireAttachmentBounds(mime, ByteArray(64) { 1 }, filename) }
        }
    }

    @Test
    fun boundsRejectEmptyCiphertext() {
        val filename = VaultPayloads.toBase64Url(ByteArray(29) { it.toByte() })
        assertRejected {
            VaultPayloads.requireAttachmentBounds("image/png", ByteArray(0), filename)
        }
    }

    @Test(timeout = 120_000)
    fun boundsRejectCiphertextOverTheTenMebibyteCap() {
        val filename = VaultPayloads.toBase64Url(ByteArray(29) { it.toByte() })
        // Exactly at the cap is fine (the cap applies to the ciphertext)...
        VaultPayloads.requireAttachmentBounds(
            "image/png",
            ByteArray(PayloadCipher.MAX_ATTACHMENT_BYTES) { 1 },
            filename,
        )
        // ...one byte over is rejected before it reaches the wire.
        assertRejected {
            VaultPayloads.requireAttachmentBounds(
                "image/png",
                ByteArray(PayloadCipher.MAX_ATTACHMENT_BYTES + 1) { 1 },
                filename,
            )
        }
    }

    @Test
    fun boundsRejectMalformedOrOutOfRangeFilename() {
        val ciphertext = ByteArray(64) { 1 }
        // Not valid base64url.
        assertRejected {
            VaultPayloads.requireAttachmentBounds("image/png", ciphertext, "!!!not-base64!!!")
        }
        // Decoded length below the 29-byte floor (28 overhead + ≥1 plaintext).
        assertRejected {
            VaultPayloads.requireAttachmentBounds(
                "image/png",
                ciphertext,
                VaultPayloads.toBase64Url(ByteArray(28)),
            )
        }
        // Decoded length above the 255-byte ceiling.
        assertRejected {
            VaultPayloads.requireAttachmentBounds(
                "image/png",
                ciphertext,
                VaultPayloads.toBase64Url(ByteArray(256)),
            )
        }
        // Boundaries themselves are accepted.
        VaultPayloads.requireAttachmentBounds(
            "image/png", ciphertext, VaultPayloads.toBase64Url(ByteArray(29))
        )
        VaultPayloads.requireAttachmentBounds(
            "image/png", ciphertext, VaultPayloads.toBase64Url(ByteArray(255))
        )
    }

    @Test
    fun parseAttachmentMatchesContractFields() {
        val entry = VaultPayloads.parseAttachment(
            JSONObject(
                """
                {
                  "id": 5,
                  "vault_item_id": 42,
                  "encrypted_filename": "QUJDREVG",
                  "mime_type": "image/webp",
                  "size": 200028,
                  "created_at": "2026-09-02T15:40:10Z"
                }
                """.trimIndent()
            )
        )
        assertEquals(5L, entry.id)
        assertEquals(42L, entry.vaultItemId)
        assertEquals("QUJDREVG", entry.encryptedFilenameB64)
        assertEquals("image/webp", entry.mimeType)
        assertEquals(200028L, entry.size)
        assertEquals("2026-09-02T15:40:10Z", entry.createdAt)
    }

    @Test
    fun parseAttachmentListReadsTheAttachmentsArray() {
        val list = VaultPayloads.parseAttachmentList(
            JSONObject(
                """
                {
                  "attachments": [
                    {"id": 2, "vault_item_id": 7, "encrypted_filename": "AA",
                     "mime_type": "image/png", "size": 10, "created_at": "2026-09-02T00:00:00Z"},
                    {"id": 1, "vault_item_id": 7, "encrypted_filename": "BB",
                     "mime_type": "image/gif", "size": 20, "created_at": "2026-09-01T00:00:00Z"}
                  ]
                }
                """.trimIndent()
            )
        )
        assertEquals(listOf(2L, 1L), list.map { it.id })
        assertEquals(7L, list[0].vaultItemId)

        // A missing "attachments" array is a contract violation, not an empty list.
        try {
            VaultPayloads.parseAttachmentList(JSONObject("""{}"""))
            fail("a missing attachments array must be rejected")
        } catch (expected: ApiError.Internal) {
            // expected
        }
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            fail("expected an IllegalArgumentException rejection")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }
}
