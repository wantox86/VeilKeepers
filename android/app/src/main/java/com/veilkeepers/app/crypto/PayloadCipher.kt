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

    /** GCM authentication tag length in bytes (128-bit). */
    const val TAG_BYTES = TAG_BITS / 8

    /** Bytes every encrypted blob adds over its plaintext (nonce + tag). */
    const val CIPHER_OVERHEAD_BYTES = NONCE_BYTES + TAG_BYTES

    /** Decoded-size ceiling for encrypted category names (backend bound). */
    const val MAX_NAME_BYTES = 255

    /** Decoded-size ceiling for encrypted item payloads (backend bound, 1 MiB). */
    const val MAX_PAYLOAD_BYTES = 1024 * 1024

    /**
     * Ceiling for an encrypted attachment (backend VK_ATTACHMENT_MAX_BYTES,
     * spec-1.md §B.6: 10 MiB). The bound applies to the CIPHERTEXT the server
     * stores, so the accepted plaintext maximum is this minus
     * [CIPHER_OVERHEAD_BYTES] — the client check mirrors the server's
     * MaxBytesReader so an oversized file is rejected before any upload.
     */
    const val MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024

    /**
     * Decoded-size ceiling for an encrypted attachment filename
     * (backend encrypted_filename VARBINARY(255)). Like the category name,
     * the bound applies to the CIPHERTEXT, so the accepted plaintext maximum
     * is [MAX_FILENAME_BYTES] − [CIPHER_OVERHEAD_BYTES].
     */
    const val MAX_FILENAME_BYTES = 255

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
     * 1..255-byte bound BEFORE anything reaches the network. The bound
     * applies to the CIPHERTEXT (nonce + tag included), so the accepted
     * plaintext maximum is [MAX_NAME_BYTES] − [CIPHER_OVERHEAD_BYTES].
     */
    fun encryptName(name: String, vk: ByteArray): ByteArray {
        val bytes = name.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty()) { "category name must not be empty" }
        require(bytes.size + CIPHER_OVERHEAD_BYTES <= MAX_NAME_BYTES) {
            "category name is too long: it must fit in " +
                "${MAX_NAME_BYTES - CIPHER_OVERHEAD_BYTES} bytes before encryption"
        }
        return encrypt(bytes, vk)
    }

    /**
     * Encrypts an item [payloadJson], enforcing the backend's decoded
     * 1..1 MiB bound BEFORE anything reaches the network. The bound applies
     * to the CIPHERTEXT (nonce + tag included), so the accepted plaintext
     * maximum is [MAX_PAYLOAD_BYTES] − [CIPHER_OVERHEAD_BYTES] — checking
     * plaintext alone would fail with a misleading error in that 28-byte gap.
     */
    fun encryptPayload(payloadJson: String, vk: ByteArray): ByteArray {
        val bytes = payloadJson.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty()) { "item payload must not be empty" }
        require(bytes.size + CIPHER_OVERHEAD_BYTES <= MAX_PAYLOAD_BYTES) {
            "item payload is too large: it must fit in " +
                "${MAX_PAYLOAD_BYTES - CIPHER_OVERHEAD_BYTES} bytes before encryption"
        }
        return encrypt(bytes, vk)
    }

    /**
     * Encrypts an attachment's [plaintext] bytes under [vk], enforcing the
     * backend's decoded ≤ [MAX_ATTACHMENT_BYTES] bound BEFORE any upload. The
     * bound applies to the CIPHERTEXT (nonce + tag included), so the accepted
     * plaintext maximum is [MAX_ATTACHMENT_BYTES] − [CIPHER_OVERHEAD_BYTES];
     * checking the plaintext alone would fail server-side in that 28-byte gap.
     */
    fun encryptAttachment(plaintext: ByteArray, vk: ByteArray): ByteArray {
        require(plaintext.isNotEmpty()) { "attachment must not be empty" }
        require(plaintext.size + CIPHER_OVERHEAD_BYTES <= MAX_ATTACHMENT_BYTES) {
            "attachment is too large: it must fit in " +
                "${MAX_ATTACHMENT_BYTES - CIPHER_OVERHEAD_BYTES} bytes before encryption"
        }
        return encrypt(plaintext, vk)
    }

    /**
     * Decrypts an attachment ciphertext produced by [encryptAttachment].
     * Semantically identical to [decrypt]; the distinct name keeps attachment
     * call sites self-documenting.
     */
    fun decryptAttachment(blob: ByteArray, vk: ByteArray): ByteArray = decrypt(blob, vk)

    /**
     * Encrypts an attachment [filename], enforcing the backend's decoded
     * ≤ [MAX_FILENAME_BYTES] bound BEFORE any upload. The resulting ciphertext
     * is at least [CIPHER_OVERHEAD_BYTES] + 1 bytes (the backend's minimum of
     * 29), so a non-empty name always satisfies the server's lower bound.
     */
    fun encryptFilename(filename: String, vk: ByteArray): ByteArray {
        val bytes = filename.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty()) { "attachment filename must not be empty" }
        require(bytes.size + CIPHER_OVERHEAD_BYTES <= MAX_FILENAME_BYTES) {
            "attachment filename is too long: it must fit in " +
                "${MAX_FILENAME_BYTES - CIPHER_OVERHEAD_BYTES} bytes before encryption"
        }
        return encrypt(bytes, vk)
    }

    /**
     * Decrypts an attachment filename produced by [encryptFilename].
     *
     * @throws GeneralSecurityException when the blob fails GCM authentication
     * (wrong VK, tampering); callers degrade to a static marker, never crash.
     */
    fun decryptFilename(blob: ByteArray, vk: ByteArray): String = decryptToString(blob, vk)

    private fun gcm(mode: Int, vk: ByteArray, nonce: ByteArray): Cipher {
        require(vk.size == KEY_BYTES) { "vault key must be $KEY_BYTES bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(vk, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        return cipher
    }
}
