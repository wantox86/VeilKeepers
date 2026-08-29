package com.veilkeepers.app.crypto

import java.security.MessageDigest
import java.util.Base64

/**
 * auth_hash = SHA-256(verifier) (spec-1.md §A.1 step 6) plus the base64
 * helpers shared by the transport layer.
 *
 * Uses java.util.Base64 so the code runs unchanged in plain JVM unit tests;
 * java.util.Base64 is available on Android API 26+ (minSdk is 26).
 */
object AuthHash {
    /** auth_hash length in bytes (SHA-256 digest). */
    const val BYTES = 32

    /** Computes SHA-256([verifier]) → 32 bytes. */
    fun of(verifier: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(verifier)

    /** Standard-alphabet base64 (with padding), matching Go's base64.StdEncoding. */
    fun toBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** Decodes standard-alphabet base64 (with padding). */
    fun fromBase64(encoded: String): ByteArray = Base64.getDecoder().decode(encoded)
}
