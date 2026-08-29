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
        // Accepted plaintext max = limit − 28 (12-byte nonce + 16-byte tag):
        // the wire bound is enforced on the CIPHERTEXT size.
        val payload = "a".repeat(
            PayloadCipher.MAX_PAYLOAD_BYTES - PayloadCipher.CIPHER_OVERHEAD_BYTES
        )
        val blob = PayloadCipher.encryptPayload(payload, vk)
        assertEquals(PayloadCipher.MAX_PAYLOAD_BYTES, blob.size)
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
        assertEquals(28, PayloadCipher.CIPHER_OVERHEAD_BYTES) // 12 nonce + 16 tag

        // Accepted plaintext max = 255 − 28 = 227 bytes (the 255-byte bound
        // applies to the ciphertext: nonce + tag included)...
        val maxName = "n".repeat(PayloadCipher.MAX_NAME_BYTES - PayloadCipher.CIPHER_OVERHEAD_BYTES)
        val blob = PayloadCipher.encryptName(maxName, vk)
        assertEquals(PayloadCipher.MAX_NAME_BYTES, blob.size)
        assertEquals(maxName, PayloadCipher.decryptToString(blob, vk))

        // ...228 bytes rejected...
        try {
            PayloadCipher.encryptName(
                "n".repeat(PayloadCipher.MAX_NAME_BYTES - PayloadCipher.CIPHER_OVERHEAD_BYTES + 1),
                vk,
            )
            fail("plaintext beyond limit − 28 must be rejected")
        } catch (expected: IllegalArgumentException) {
            // display-ready message naming the real plaintext ceiling
            assertTrue(expected.message!!.contains("227"))
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
        // The wire bound (mirroring backend TestVaultItemPayloadBoundaries)
        // applies to the CIPHERTEXT, so the accepted plaintext max is
        // 1 MiB − 28 (12 nonce + 16 tag).
        val maxPlaintext = PayloadCipher.MAX_PAYLOAD_BYTES - PayloadCipher.CIPHER_OVERHEAD_BYTES

        // Exactly at the plaintext ceiling: the blob comes out at exactly 1 MiB.
        val atLimit = "p".repeat(maxPlaintext)
        val blob = PayloadCipher.encryptPayload(atLimit, vk)
        assertEquals(PayloadCipher.MAX_PAYLOAD_BYTES, blob.size)
        assertEquals(atLimit, PayloadCipher.decryptToString(blob, vk))

        // One byte over → rejected client-side, before any network call
        // (previously this fell into the misleading 28-byte gap).
        try {
            PayloadCipher.encryptPayload("p".repeat(maxPlaintext + 1), vk)
            fail("plaintext beyond limit − 28 must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains(maxPlaintext.toString()))
        }

        try {
            PayloadCipher.encryptPayload("", vk)
            fail("empty payload must be rejected")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }
}
