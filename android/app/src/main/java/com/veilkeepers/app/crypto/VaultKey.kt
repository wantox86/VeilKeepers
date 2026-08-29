package com.veilkeepers.app.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Vault Encryption Key (VK) generation and AES-256-GCM wrapping with the KEK
 * (spec-1.md §A.1 step 8).
 *
 * Wrapped blob layout: `nonce (12) || ciphertext+tag (32 + 16)` = 60 bytes,
 * well within the backend's 128-byte `wrapped_vault_key` bound. Every wrap
 * uses a fresh random 96-bit nonce.
 */
object VaultKey {
    /** VK length in bytes (256-bit). */
    const val KEY_BYTES = 32

    /** GCM nonce length in bytes (96-bit). */
    const val NONCE_BYTES = 12

    /** GCM authentication tag length in bits. */
    private const val TAG_BITS = 128

    /** Expected wrapped blob size: nonce + VK + tag. */
    const val WRAPPED_BYTES = NONCE_BYTES + KEY_BYTES + TAG_BITS / 8

    /** Generates a fresh random 256-bit Vault Encryption Key. */
    fun generate(): ByteArray = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }

    /**
     * Wraps [vk] under [kek] with AES-256-GCM and a fresh random nonce.
     * Returns `nonce || ciphertext(+tag)` (60 bytes for a 32-byte VK).
     */
    fun wrap(vk: ByteArray, kek: ByteArray): ByteArray {
        require(vk.size == KEY_BYTES) { "vault key must be $KEY_BYTES bytes" }
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val ciphertext = gcm(Cipher.ENCRYPT_MODE, kek, nonce).doFinal(vk)
        return nonce + ciphertext
    }

    /**
     * Unwraps a `nonce || ciphertext(+tag)` blob with [kek], returning the VK.
     *
     * @throws GeneralSecurityException (incl. AEADBadTagException) when the
     * blob is malformed or the KEK is wrong — callers must treat this as
     * "wrong password", never fall back silently.
     */
    fun unwrap(blob: ByteArray, kek: ByteArray): ByteArray {
        require(blob.size > NONCE_BYTES) { "wrapped blob too short" }
        val nonce = blob.copyOfRange(0, NONCE_BYTES)
        val ciphertext = blob.copyOfRange(NONCE_BYTES, blob.size)
        return gcm(Cipher.DECRYPT_MODE, kek, nonce).doFinal(ciphertext)
    }

    private fun gcm(mode: Int, kek: ByteArray, nonce: ByteArray): Cipher {
        require(kek.size == KEY_BYTES) { "KEK must be $KEY_BYTES bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(kek, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        return cipher
    }
}
