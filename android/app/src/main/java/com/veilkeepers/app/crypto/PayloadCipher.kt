package com.veilkeepers.app.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Client-side encryption of vault item payloads and category names with the
 * VaultKey (VK) — spec-1.md §A.3.
 *
 * Blob layout mirrors the VK wrapping construction: `nonce (12) ||
 * ciphertext + GCM tag`, standard base64 only at the transport boundary.
 * Every [encrypt] uses a fresh random 96-bit nonce.
 *
 * Deliberately separate from [VaultKey] (which handles KEK wrapping of the
 * VK): KEK and VK semantics never share a code path.
 */
object PayloadCipher {
    /** VK length in bytes (256-bit). */
    const val KEY_BYTES = 32

    /** GCM nonce length in bytes (96-bit). */
    const val NONCE_BYTES = 12

    /** GCM authentication tag length in bits. */
    private const val TAG_BITS = 128

    /** Decoded-size ceiling for encrypted category names (backend bound). */
    const val MAX_NAME_BYTES = 255

    /** Decoded-size ceiling for encrypted item payloads (backend bound, 1 MiB). */
    const val MAX_PAYLOAD_BYTES = 1024 * 1024

    /**
     * Encrypts [plaintext] under [vk] with AES-256-GCM and a fresh random
     * nonce. Returns `nonce || ciphertext(+tag)`.
     */
    fun encrypt(plaintext: ByteArray, vk: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        return nonce + gcm(Cipher.ENCRYPT_MODE, vk, nonce).doFinal(plaintext)
    }

    /** UTF-8 convenience variant of [encrypt]. */
    fun encrypt(plaintext: String, vk: ByteArray): ByteArray =
        encrypt(plaintext.toByteArray(Charsets.UTF_8), vk)

    /**
     * Decrypts a `nonce || ciphertext(+tag)` [blob] with [vk].
     *
     * @throws GeneralSecurityException (incl. AEADBadTagException) when the
     * blob is malformed, tampered, or the VK is wrong — callers must treat
     * this as "undecryptable", never crash, never expose blob details.
     */
    fun decrypt(blob: ByteArray, vk: ByteArray): ByteArray {
        require(blob.size > NONCE_BYTES) { "encrypted blob too short" }
        val nonce = blob.copyOfRange(0, NONCE_BYTES)
        val ciphertext = blob.copyOfRange(NONCE_BYTES, blob.size)
        return gcm(Cipher.DECRYPT_MODE, vk, nonce).doFinal(ciphertext)
    }

    /** UTF-8 convenience variant of [decrypt]. */
    fun decryptToString(blob: ByteArray, vk: ByteArray): String =
        String(decrypt(blob, vk), Charsets.UTF_8)

    /**
     * Encrypts a category [name], enforcing the backend's decoded
     * 1..255-byte bound BEFORE anything reaches the network.
     */
    fun encryptName(name: String, vk: ByteArray): ByteArray {
        val bytes = name.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty()) { "category name must not be empty" }
        require(bytes.size <= MAX_NAME_BYTES) {
            "category name exceeds $MAX_NAME_BYTES bytes"
        }
        return encrypt(bytes, vk)
    }

    /**
     * Encrypts an item [payloadJson], enforcing the backend's decoded
     * 1..1 MiB bound BEFORE anything reaches the network.
     */
    fun encryptPayload(payloadJson: String, vk: ByteArray): ByteArray {
        val bytes = payloadJson.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty()) { "item payload must not be empty" }
        require(bytes.size <= MAX_PAYLOAD_BYTES) {
            "item payload exceeds the ${MAX_PAYLOAD_BYTES}-byte (1 MiB) limit"
        }
        return encrypt(bytes, vk)
    }

    private fun gcm(mode: Int, vk: ByteArray, nonce: ByteArray): Cipher {
        require(vk.size == KEY_BYTES) { "vault key must be $KEY_BYTES bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(vk, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        return cipher
    }
}
