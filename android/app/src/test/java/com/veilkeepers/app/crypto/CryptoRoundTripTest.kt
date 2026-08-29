package com.veilkeepers.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.GeneralSecurityException

/**
 * Crypto round-trip tests (Sprint 3 acceptance). Small KDF parameters keep
 * the suite fast; one full-spec-param test runs with a generous timeout.
 */
class CryptoRoundTripTest {

    /** Tiny params for fast deterministic tests. */
    private val testParams = KdfParams(m = 1024, t = 1, p = 1)

    private val fixedSalt = ByteArray(Argon2Kdf.SALT_BYTES) { (it + 1).toByte() }

    @Test
    fun argon2idIsDeterministicForFixedSaltAndSmallParams() {
        val password = "correct horse battery staple".toByteArray()
        val first = Argon2Kdf.derive(password, fixedSalt, testParams)
        val second = Argon2Kdf.derive(password, fixedSalt, testParams)
        assertEquals(Argon2Kdf.OUTPUT_BYTES, first.size)
        assertArrayEquals(first, second)

        // A different salt must change the output entirely.
        val otherSalt = fixedSalt.copyOf().also { it[0] = 0 }
        val third = Argon2Kdf.derive(password, otherSalt, testParams)
        assertFalse(first.contentEquals(third))
    }

    @Test(timeout = 300_000)
    fun fullSpecParamsProduce64Bytes() {
        val derived = Argon2Kdf.derive(
            "veil-keepers".toByteArray(),
            fixedSalt,
            KdfParams.SPEC, // m=65536 KiB, t=3, p=4 — may take several seconds
        )
        assertEquals(64, derived.size)
    }

    @Test
    fun splitYieldsKekAndVerifierOf32BytesEach() {
        val derived = Argon2Kdf.derive("pw".toByteArray(), fixedSalt, testParams)
        val (kek, verifier) = Argon2Kdf.split(derived)
        assertEquals(32, kek.size)
        assertEquals(32, verifier.size)
        assertArrayEquals(derived.copyOfRange(0, 32), kek)
        assertArrayEquals(derived.copyOfRange(32, 64), verifier)
    }

    @Test
    fun authHashIs32BytesAndDeterministic() {
        val verifier = ByteArray(32) { it.toByte() }
        val hash1 = AuthHash.of(verifier)
        val hash2 = AuthHash.of(verifier)
        assertEquals(32, hash1.size)
        assertArrayEquals(hash1, hash2)

        // base64 helpers round-trip (standard alphabet, Go-compatible).
        val encoded = AuthHash.toBase64(hash1)
        assertArrayEquals(hash1, AuthHash.fromBase64(encoded))
    }

    @Test
    fun wrapThenUnwrapReturnsTheSameVaultKey() {
        val vk = VaultKey.generate()
        val kek = ByteArray(32) { (it * 3).toByte() }
        val blob = VaultKey.wrap(vk, kek)
        assertEquals(VaultKey.WRAPPED_BYTES, blob.size)
        assertArrayEquals(vk, VaultKey.unwrap(blob, kek))
    }

    @Test
    fun unwrapWithWrongKekThrows() {
        val vk = VaultKey.generate()
        val kek = ByteArray(32) { 7 }
        val wrongKek = ByteArray(32) { 8 }
        val blob = VaultKey.wrap(vk, kek)
        try {
            VaultKey.unwrap(blob, wrongKek)
            fail("unwrap with the wrong KEK must throw")
        } catch (expected: GeneralSecurityException) {
            // AEADBadTagException is a GeneralSecurityException — expected.
        }
    }

    @Test
    fun twoWrapsUseFreshNonces() {
        val vk = VaultKey.generate()
        val kek = ByteArray(32) { 1 }
        val blob1 = VaultKey.wrap(vk, kek)
        val blob2 = VaultKey.wrap(vk, kek)
        val nonce1 = blob1.copyOfRange(0, VaultKey.NONCE_BYTES)
        val nonce2 = blob2.copyOfRange(0, VaultKey.NONCE_BYTES)
        assertFalse(nonce1.contentEquals(nonce2))
        assertFalse(blob1.contentEquals(blob2))
    }

    @Test
    fun tamperedBlobFailsAuthentication() {
        val vk = VaultKey.generate()
        val kek = ByteArray(32) { 5 }
        val blob = VaultKey.wrap(vk, kek)

        // Flip one bit inside the ciphertext portion — GCM tag must reject it.
        val tamperedCiphertext = blob.copyOf().also {
            it[VaultKey.NONCE_BYTES + 3] = (it[VaultKey.NONCE_BYTES + 3] + 1).toByte()
        }
        try {
            VaultKey.unwrap(tamperedCiphertext, kek)
            fail("bit-flipped ciphertext must fail GCM authentication")
        } catch (expected: GeneralSecurityException) {
            // expected
        }

        // Flip one bit inside the nonce — GCM tag must reject it too.
        val tamperedNonce = blob.copyOf().also { it[0] = (it[0] + 1).toByte() }
        try {
            VaultKey.unwrap(tamperedNonce, kek)
            fail("bit-flipped nonce must fail GCM authentication")
        } catch (expected: GeneralSecurityException) {
            // expected
        }
    }

    @Test
    fun payloadSizesFitBackendLimits() {
        // backend/internal/auth/service.go: salt ≥16 & ≤32, wrapped ≤128.
        val salt = Argon2Kdf.randomSalt()
        assertTrue(salt.size >= 16)
        assertTrue(salt.size <= 32)

        val wrapped = VaultKey.wrap(VaultKey.generate(), ByteArray(32))
        assertTrue(wrapped.isNotEmpty())
        assertTrue(wrapped.size <= 128)

        val authHash = AuthHash.of(ByteArray(32))
        assertEquals(32, authHash.size)
    }
}
