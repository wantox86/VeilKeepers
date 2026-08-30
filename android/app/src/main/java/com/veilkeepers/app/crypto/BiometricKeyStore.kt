package com.veilkeepers.app.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.InvalidKeyException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * Biometric key wrapping (spec-1.md §A.1 local storage, spec.md §25): the VK
 * is wrapped by an Android Keystore AES key with
 * `setUserAuthenticationRequired(true)`; the blob lives in
 * EncryptedSharedPreferences ([com.veilkeepers.app.data.SessionStorage]).
 * Biometrics NEVER authenticate against the backend — they only release local
 * key material.
 */

/**
 * Cipher provider abstraction: the production engine comes from the Android
 * Keystore ([AndroidBiometricKeyStore]); JVM tests substitute a plain JCE
 * SecretKeySpec AES/GCM engine.
 */
interface KeyWrapEngine {
    /** ENCRYPT_MODE cipher for enrollment (passed as BiometricPrompt CryptoObject). */
    fun createWrapCipher(): Cipher

    /** DECRYPT_MODE cipher bound to [nonce] for unlock (passed as CryptoObject). */
    fun createUnwrapCipher(nonce: ByteArray): Cipher
}

/**
 * Blob codec for the biometric-wrapped VK. Layout is
 * `base64(nonce(12) || ciphertext+tag)` — the same convention as
 * [VaultKey.wrap]/[VaultKey.unwrap].
 */
object BiometricBlob {
    /** Nonce length in bytes (96-bit GCM). */
    const val NONCE_BYTES = 12

    fun encode(nonce: ByteArray, ciphertextWithTag: ByteArray): String {
        require(nonce.size == NONCE_BYTES) { "nonce must be $NONCE_BYTES bytes" }
        return AuthHash.toBase64(nonce + ciphertextWithTag)
    }

    /** @return nonce to ciphertext(+tag) split. */
    fun decode(blobB64: String): Pair<ByteArray, ByteArray> {
        val raw = AuthHash.fromBase64(blobB64)
        require(raw.size > NONCE_BYTES) { "biometric blob too short" }
        return raw.copyOfRange(0, NONCE_BYTES) to raw.copyOfRange(NONCE_BYTES, raw.size)
    }
}

/**
 * Pure JVM-testable core of the biometric enrollment/unlock. Holds NO Android
 * dependencies beyond [KeyWrapEngine]: persistence goes through the
 * [com.veilkeepers.app.data.SessionStorage] interface, cipher work through the
 * injected engine. All `doFinal` calls happen inside caller-supplied ciphers,
 * which the Android controller only obtains inside BiometricPrompt success
 * callbacks.
 */
class BiometricVaultCore(
    private val engine: KeyWrapEngine,
    private val storage: com.veilkeepers.app.data.SessionStorage,
) {

    /** Unlock outcome. */
    sealed class UnlockResult {
        /** VK released from the blob. */
        class Unlocked(val vaultKey: ByteArray) : UnlockResult()

        /**
         * Keystore key invalidated (biometric enrollment changed, user not
         * authenticated): the enrollment was wiped; fall back to password.
         */
        object Invalidated : UnlockResult()

        /** No enrollment or corrupt blob: password fallback, nothing wiped. */
        class Unavailable(val message: String) : UnlockResult()
    }

    /** Phase-1 outcome: cipher ready for the BiometricPrompt CryptoObject, or a failure. */
    sealed class UnlockPrep {
        class Ready(val cipher: Cipher) : UnlockPrep()
        class Failed(val result: UnlockResult) : UnlockPrep()
    }

    /** True when an enrollment exists (blob stored + flag set). */
    fun hasEnrollment(): Boolean =
        storage.biometricEnabled && storage.biometricWrappedVkB64.isNotEmpty()

    /**
     * Persists a freshly wrapped VK. Called ONLY from a BiometricPrompt
     * success callback with the cipher that just passed authentication;
     * [nonce] is the cipher's IV, [ciphertextWithTag] the `doFinal` output.
     */
    fun storeWrapped(nonce: ByteArray, ciphertextWithTag: ByteArray) {
        storage.biometricWrappedVkB64 = BiometricBlob.encode(nonce, ciphertextWithTag)
        storage.biometricEnabled = true
    }

    /**
     * Phase 1 (BEFORE the prompt): decode the stored blob and build the
     * decrypt cipher for the CryptoObject. A Keystore key that has been
     * invalidated throws [InvalidKeyException] here — the enrollment is wiped
     * and the caller falls back to the password.
     */
    fun prepareUnlock(): UnlockPrep {
        if (!hasEnrollment()) return UnlockPrep.Failed(UnlockResult.Unavailable("No biometric enrollment."))
        val nonce = try {
            BiometricBlob.decode(storage.biometricWrappedVkB64).first
        } catch (e: Exception) {
            return UnlockPrep.Failed(UnlockResult.Unavailable("Biometric enrollment is corrupt."))
        }
        return try {
            UnlockPrep.Ready(engine.createUnwrapCipher(nonce))
        } catch (e: InvalidKeyException) {
            // KeyPermanentlyInvalidatedException extends InvalidKeyException.
            wipe()
            UnlockPrep.Failed(UnlockResult.Invalidated)
        }
    }

    /**
     * Phase 2 (INSIDE the BiometricPrompt success callback): runs `doFinal`
     * with the authenticated [cipher]. `UserNotAuthenticatedException`
     * extends [InvalidKeyException], so any residual invalidation is caught
     * here as well.
     */
    fun finishUnlock(cipher: Cipher): UnlockResult {
        if (!hasEnrollment()) return UnlockResult.Unavailable("No biometric enrollment.")
        val ciphertext = try {
            BiometricBlob.decode(storage.biometricWrappedVkB64).second
        } catch (e: Exception) {
            return UnlockResult.Unavailable("Biometric enrollment is corrupt.")
        }
        return try {
            UnlockResult.Unlocked(cipher.doFinal(ciphertext))
        } catch (e: InvalidKeyException) {
            wipe()
            UnlockResult.Invalidated
        } catch (e: java.security.GeneralSecurityException) {
            UnlockResult.Unavailable("Biometric unlock failed. Use your password.")
        }
    }

    /** Deletes the blob + Keystore-independent flags (disable/logout paths). */
    fun wipe() {
        storage.biometricWrappedVkB64 = ""
        storage.biometricEnabled = false
    }
}

/**
 * Production [KeyWrapEngine] over the Android Keystore: alias [ALIAS],
 * AES/GCM, `setUserAuthenticationRequired(true)` — the cipher only becomes
 * usable inside a successful BiometricPrompt.
 */
class AndroidBiometricKeyStore : KeyWrapEngine {

    override fun createWrapCipher(): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return cipher
    }

    override fun createUnwrapCipher(nonce: ByteArray): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, nonce))
        return cipher
    }

    /** Deletes the Keystore alias (enrollment teardown). Best effort. */
    fun deleteKey() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.deleteEntry(ALIAS)
        } catch (ignored: Exception) {
            // Blob/flags are wiped by BiometricVaultCore regardless.
        }
    }

    private fun secretKey(): javax.crypto.SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        /** Keystore alias holding the VK-wrapping key (docs/security/key-architecture.md §8). */
        const val ALIAS = "vk_biometric"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }
}
