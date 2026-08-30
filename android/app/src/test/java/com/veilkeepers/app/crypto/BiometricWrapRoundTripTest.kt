package com.veilkeepers.app.crypto

import com.veilkeepers.app.data.SessionStorage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.InvalidKeyException
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Plain-JCE [KeyWrapEngine]: a SecretKeySpec AES/GCM key with NO user
 * authentication, standing in for the Android Keystore key in JVM tests.
 */
private class FakeJceKeyWrapEngine : KeyWrapEngine {
    private val key = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")

    /** When true, cipher creation fails like an invalidated Keystore key. */
    var invalidated: Boolean = false

    override fun createWrapCipher(): Cipher {
        if (invalidated) throw InvalidKeyException("key invalidated")
        val nonce = ByteArray(BiometricBlob.NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        }
    }

    override fun createUnwrapCipher(nonce: ByteArray): Cipher {
        if (invalidated) throw InvalidKeyException("key invalidated")
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
        }
    }
}

/** In-memory [SessionStorage] for JVM tests (no Android Keystore). */
private class InMemoryStorage : SessionStorage {
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
    override fun deviceName(): String = "TestDevice"
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
 * Biometric VK wrapping round-trip through the injectable [KeyWrapEngine]
 * (spec-1.md §A.1 local storage): wrap → persist → unwrap equals the VK;
 * tampering fails GCM authentication; invalidation wipes the enrollment and
 * signals the password fallback. The blob layout mirrors [VaultKey]'s
 * `nonce || ciphertext+tag` convention.
 */
class BiometricWrapRoundTripTest {

    private val engine = FakeJceKeyWrapEngine()
    private val storage = InMemoryStorage()
    private val core = BiometricVaultCore(engine, storage)

    /** Enrolls exactly like the controller's success callback does. */
    private fun enroll(vk: ByteArray) {
        val cipher = engine.createWrapCipher()
        val nonce = cipher.iv
        val ciphertext = cipher.doFinal(vk)
        core.storeWrapped(nonce, ciphertext)
    }

    @Test
    fun wrapStoreUnwrapReturnsTheOriginalVaultKey() {
        val vk = VaultKey.generate()
        assertFalse(core.hasEnrollment())

        enroll(vk)
        assertTrue(core.hasEnrollment())
        assertTrue(storage.biometricEnabled)
        assertTrue(storage.biometricWrappedVkB64.isNotEmpty())

        // Blob layout: base64(nonce(12) || ciphertext+tag).
        val (nonce, ciphertext) = BiometricBlob.decode(storage.biometricWrappedVkB64)
        assertEquals(BiometricBlob.NONCE_BYTES, nonce.size)
        assertEquals(VaultKey.KEY_BYTES + 16, ciphertext.size) // VK + GCM tag

        val prep = core.prepareUnlock()
        assertTrue(prep is BiometricVaultCore.UnlockPrep.Ready)
        val result = core.finishUnlock((prep as BiometricVaultCore.UnlockPrep.Ready).cipher)
        assertTrue(result is BiometricVaultCore.UnlockResult.Unlocked)
        assertArrayEquals(vk, (result as BiometricVaultCore.UnlockResult.Unlocked).vaultKey)
    }

    @Test
    fun tamperedBlobFailsAuthenticationAndNeverReleasesAKey() {
        enroll(VaultKey.generate())

        // Flip one bit of the ciphertext portion.
        val (nonce, ciphertext) = BiometricBlob.decode(storage.biometricWrappedVkB64)
        ciphertext[0] = (ciphertext[0].toInt() xor 1).toByte()
        storage.biometricWrappedVkB64 = BiometricBlob.encode(nonce, ciphertext)

        val prep = core.prepareUnlock()
        assertTrue(prep is BiometricVaultCore.UnlockPrep.Ready)
        val result = core.finishUnlock((prep as BiometricVaultCore.UnlockPrep.Ready).cipher)
        assertTrue(result is BiometricVaultCore.UnlockResult.Unavailable)
    }

    @Test
    fun invalidKeySignalWipesEnrollmentAndSignalsPasswordFallback() {
        enroll(VaultKey.generate())
        assertTrue(core.hasEnrollment())

        engine.invalidated = true // Keystore key gone (e.g. enrollment changed)

        val prep = core.prepareUnlock()
        assertTrue(prep is BiometricVaultCore.UnlockPrep.Failed)
        val failed = (prep as BiometricVaultCore.UnlockPrep.Failed).result
        assertTrue(failed is BiometricVaultCore.UnlockResult.Invalidated)

        // Enrollment wiped → the caller falls back to password unlock.
        assertFalse(storage.biometricEnabled)
        assertEquals("", storage.biometricWrappedVkB64)
        assertFalse(core.hasEnrollment())
    }

    @Test
    fun missingEnrollmentIsUnavailableWithoutWipingAnything() {
        val prep = core.prepareUnlock()
        assertTrue(prep is BiometricVaultCore.UnlockPrep.Failed)
        val failed = (prep as BiometricVaultCore.UnlockPrep.Failed).result
        assertTrue(failed is BiometricVaultCore.UnlockResult.Unavailable)
    }

    @Test
    fun corruptBlobIsUnavailableNotInvalidated() {
        storage.biometricEnabled = true
        storage.biometricWrappedVkB64 = "!!!not-base64!!!"
        val prep = core.prepareUnlock()
        assertTrue(prep is BiometricVaultCore.UnlockPrep.Failed)
        val failed = (prep as BiometricVaultCore.UnlockPrep.Failed).result
        assertTrue(failed is BiometricVaultCore.UnlockResult.Unavailable)
    }

    @Test
    fun disableWipesBlobAndFlag() {
        enroll(VaultKey.generate())
        core.wipe()
        assertFalse(core.hasEnrollment())
        assertEquals("", storage.biometricWrappedVkB64)
        assertFalse(storage.biometricEnabled)
    }
}
