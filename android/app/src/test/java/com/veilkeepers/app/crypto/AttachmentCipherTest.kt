package com.veilkeepers.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.GeneralSecurityException

/**
 * Sprint 8 attachment crypto tests: [PayloadCipher.encryptAttachment] /
 * [PayloadCipher.decryptAttachment] round-trips, the two-sided 10 MiB
 * ciphertext cap, fresh nonce per file, and the encrypted-filename bounds
 * (29..255 bytes → the plaintext filename ceiling is 255 − 28 = 227 bytes).
 */
class AttachmentCipherTest {

    private val vk = VaultKey.generate()

    @Test
    fun attachmentRoundTripPreservesBytes() {
        // A plausible binary image body: every byte value, non-text.
        val plaintext = ByteArray(4096) { (it * 7 % 256).toByte() }
        val blob = PayloadCipher.encryptAttachment(plaintext, vk)
        assertEquals(plaintext.size + PayloadCipher.CIPHER_OVERHEAD_BYTES, blob.size)
        assertArrayEquals(plaintext, PayloadCipher.decryptAttachment(blob, vk))
    }

    @Test
    fun everyAttachmentGetsAFreshNonce() {
        val plaintext = ByteArray(64) { it.toByte() }
        val blobs = (1..32).map { PayloadCipher.encryptAttachment(plaintext, vk) }
        // 32 encryptions of identical bytes → 32 distinct blobs (unique nonces).
        assertEquals(32, blobs.map { it.toList() }.toSet().size)
        blobs.forEach { assertArrayEquals(plaintext, PayloadCipher.decryptAttachment(it, vk)) }
    }

    @Test
    fun emptyAttachmentIsRejected() {
        try {
            PayloadCipher.encryptAttachment(ByteArray(0), vk)
            fail("an empty attachment must be rejected")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test(timeout = 180_000)
    fun attachmentLimitPinsTheTenMebibyteCiphertextBoundary() {
        assertEquals(10 * 1024 * 1024, PayloadCipher.MAX_ATTACHMENT_BYTES)
        val maxPlaintext = PayloadCipher.MAX_ATTACHMENT_BYTES - PayloadCipher.CIPHER_OVERHEAD_BYTES

        // Exactly at the plaintext ceiling → the blob is exactly 10 MiB.
        val atLimit = ByteArray(maxPlaintext) { 1 }
        val blob = PayloadCipher.encryptAttachment(atLimit, vk)
        assertEquals(PayloadCipher.MAX_ATTACHMENT_BYTES, blob.size)

        // One byte over → rejected client-side, before any upload.
        try {
            PayloadCipher.encryptAttachment(ByteArray(maxPlaintext + 1) { 1 }, vk)
            fail("plaintext beyond limit − 28 must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains(maxPlaintext.toString()))
        }
    }

    @Test
    fun attachmentDecryptWithWrongKeyThrows() {
        val blob = PayloadCipher.encryptAttachment(ByteArray(32) { it.toByte() }, vk)
        try {
            PayloadCipher.decryptAttachment(blob, VaultKey.generate())
            fail("decrypt with the wrong VK must throw")
        } catch (expected: GeneralSecurityException) {
            // AEADBadTagException — expected
        }
    }

    @Test
    fun filenameRoundTripPreservesUtf8() {
        val filename = "passport-scan 密码 🔐.png"
        val blob = PayloadCipher.encryptFilename(filename, vk)
        // Ciphertext = nonce + tag + the UTF-8 filename bytes.
        assertEquals(
            filename.toByteArray(Charsets.UTF_8).size + PayloadCipher.CIPHER_OVERHEAD_BYTES,
            blob.size,
        )
        assertEquals(filename, PayloadCipher.decryptFilename(blob, vk))
    }

    @Test
    fun filenameBoundsPinThe255ByteCiphertextCeiling() {
        assertEquals(255, PayloadCipher.MAX_FILENAME_BYTES)
        val maxName = "n".repeat(PayloadCipher.MAX_FILENAME_BYTES - PayloadCipher.CIPHER_OVERHEAD_BYTES)
        val blob = PayloadCipher.encryptFilename(maxName, vk)
        assertEquals(PayloadCipher.MAX_FILENAME_BYTES, blob.size)
        assertEquals(maxName, PayloadCipher.decryptFilename(blob, vk))

        // 228 plaintext bytes → rejected.
        try {
            PayloadCipher.encryptFilename(
                "n".repeat(PayloadCipher.MAX_FILENAME_BYTES - PayloadCipher.CIPHER_OVERHEAD_BYTES + 1),
                vk,
            )
            fail("filename plaintext beyond 227 bytes must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("227"))
        }

        // Empty rejected; multibyte counts as UTF-8 bytes (128 × 'é' = 256 > 227).
        try {
            PayloadCipher.encryptFilename("", vk)
            fail("empty filename must be rejected")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
        try {
            PayloadCipher.encryptFilename("é".repeat(128), vk)
            fail("256 UTF-8 bytes must be rejected")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun ciphertextNeverContainsThePlaintextFilenameOrBytes() {
        val filename = "SECRET-DO-NOT-LEAK.png"
        val plaintext = filename.toByteArray(Charsets.UTF_8)
        val nameBlob = PayloadCipher.encryptFilename(filename, vk)
        val fileBlob = PayloadCipher.encryptAttachment(plaintext, vk)

        val nameText = String(nameBlob, Charsets.ISO_8859_1)
        val fileText = String(fileBlob, Charsets.ISO_8859_1)
        assertFalse(nameText.contains("SECRET-DO-NOT-LEAK"))
        assertFalse(fileText.contains("SECRET-DO-NOT-LEAK"))
        // The base64 of the plaintext must not appear either.
        assertFalse(nameText.contains(AuthHash.toBase64(plaintext)))
    }
}
