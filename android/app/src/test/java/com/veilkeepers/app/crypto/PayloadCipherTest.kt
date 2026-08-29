package com.veilkeepers.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.GeneralSecurityException

/**
 * PayloadCipher tests (Sprint 5): UTF-8/empty/max-size round-trips, fresh
 * nonces, GCM authentication failures, and the backend bounds enforcement
 * (255-byte names, 1 MiB payloads — mirrored from docs/api/vault.md).
 */
class PayloadCipherTest {

    private val vk = VaultKey.generate()

    @Test
    fun roundTripPreservesUtf8Plaintext() {
        val plaintext = "Diária 密码 🔐 — atrás do véu"
        val blob = PayloadCipher.encrypt(plaintext, vk)
        assertEquals(PayloadCipher.NONCE_BYTES + plaintext.toByteArray().size + 16, blob.size)
        assertEquals(plaintext, PayloadCipher.decryptToString(blob, vk))
    }

    @Test
    fun roundTripByteArrayVariants() {
        val plaintext = ByteArray(1000) { (it % 256).toByte() }
        val blob = PayloadCipher.encrypt(plaintext, vk)
        assertArrayEquals(plaintext, PayloadCipher.decrypt(blob, vk))
    }

    @Test
    fun roundTripEmptyPlaintext() {
        // GCM authenticates empty plaintext too (tag-only blob).
        val blob = PayloadCipher.encrypt(ByteArray(0), vk)
        assertEquals(PayloadCipher.NONCE_BYTES + 16, blob.size)
        assertArrayEquals(ByteArray(0), PayloadCipher.decrypt(blob, vk))
        assertEquals("", PayloadCipher.decryptToString(blob, vk))
    }

    @Test
    fun roundTripMaxSizePayload() {
        val payload = "a".repeat(PayloadCipher.MAX_PAYLOAD_BYTES) // exactly 1 MiB
        val blob = PayloadCipher.encryptPayload(payload, vk)
        assertEquals(payload, PayloadCipher.decryptToString(blob, vk))
    }

    @Test
    fun freshNonceEveryCall() {
        val plaintext = "same plaintext".toByteArray()
        val nonces = (1..64).map {
            val blob = PayloadCipher.encrypt(plaintext, vk)
            blob.copyOfRange(0, PayloadCipher.NONCE_BYTES).toList()
        }.toSet()
        // 64 encryptions of identical plaintext → 64 distinct nonces and
        // therefore 64 distinct blobs.
        assertEquals(64, nonces.size)
    }

    @Test
    fun twoEncryptsOfSamePlaintextDiffer() {
        val blob1 = PayloadCipher.encrypt("veiled", vk)
        val blob2 = PayloadCipher.encrypt("veiled", vk)
        assertFalse(blob1.contentEquals(blob2))
        assertEquals("veiled", PayloadCipher.decryptToString(blob1, vk))
        assertEquals("veiled", PayloadCipher.decryptToString(blob2, vk))
    }

    @Test
    fun decryptWithWrongKeyThrows() {
        val blob = PayloadCipher.encrypt("secret", vk)
        val wrongKey = VaultKey.generate()
        try {
            PayloadCipher.decrypt(blob, wrongKey)
            fail("decrypt with the wrong VK must throw")
        } catch (expected: GeneralSecurityException) {
            // AEADBadTagException is a GeneralSecurityException — expected.
        }
    }

    @Test
    fun tamperedCiphertextFailsAuthentication() {
        val blob = PayloadCipher.encrypt("secret", vk)
        val tampered = blob.copyOf().also {
            it[PayloadCipher.NONCE_BYTES + 1] = (it[PayloadCipher.NONCE_BYTES + 1] + 1).toByte()
        }
        try {
            PayloadCipher.decrypt(tampered, vk)
            fail("tampered ciphertext must fail GCM authentication")
        } catch (expected: GeneralSecurityException) {
            // expected
        }
    }

    @Test
    fun tamperedNonceFailsAuthentication() {
        val blob = PayloadCipher.encrypt("secret", vk)
        val tampered = blob.copyOf().also { it[0] = (it[0] + 1).toByte() }
        try {
            PayloadCipher.decrypt(tampered, vk)
            fail("tampered nonce must fail GCM authentication")
        } catch (expected: GeneralSecurityException) {
            // expected
        }
    }

    @Test
    fun shortBlobIsRejected() {
        try {
            PayloadCipher.decrypt(ByteArray(PayloadCipher.NONCE_BYTES), vk)
            fail("a blob with no ciphertext must be rejected")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun keyLengthIsEnforced() {
        for (badKey in listOf(ByteArray(0), ByteArray(16), ByteArray(31), ByteArray(33))) {
            try {
                PayloadCipher.encrypt("x", badKey)
                fail("key of ${badKey.size} bytes must be rejected")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("32"))
            }
        }
    }

    @Test
    fun nameBoundsEnforced() {
        // Exactly 255 bytes accepted...
        val maxName = "n".repeat(PayloadCipher.MAX_NAME_BYTES)
        assertEquals(maxName, PayloadCipher.decryptToString(PayloadCipher.encryptName(maxName, vk), vk))

        // ...256 bytes rejected...
        try {
            PayloadCipher.encryptName("n".repeat(PayloadCipher.MAX_NAME_BYTES + 1), vk)
            fail("256-byte name must be rejected")
        } catch (expected: IllegalArgumentException) {
            // expected
        }

        // ...and empty rejected. Multibyte chars count as UTF-8 bytes: 128 ×
        // 'é' (2 bytes each) = 256 bytes → rejected despite 128 "chars".
        try {
            PayloadCipher.encryptName("é".repeat(128), vk)
            fail("256 UTF-8 bytes must be rejected")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
        try {
            PayloadCipher.encryptName("", vk)
            fail("empty name must be rejected")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test(timeout = 120_000)
    fun payloadBoundsEnforcedAtExactlyOneMebibyte() {
        // Exactly 1 MiB accepted (mirrors backend TestVaultItemPayloadBoundaries)...
        val atLimit = "p".repeat(PayloadCipher.MAX_PAYLOAD_BYTES)
        val blob = PayloadCipher.encryptPayload(atLimit, vk)
        assertEquals(atLimit, PayloadCipher.decryptToString(blob, vk))

        // ...1 MiB + 1 byte rejected client-side, before any network call.
        try {
            PayloadCipher.encryptPayload("p".repeat(PayloadCipher.MAX_PAYLOAD_BYTES + 1), vk)
            fail("1 MiB + 1 byte payload must be rejected")
        } catch (expected: IllegalArgumentException) {
            // expected
        }

        try {
            PayloadCipher.encryptPayload("", vk)
            fail("empty payload must be rejected")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }
}
