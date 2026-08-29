package com.veilkeepers.app.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/**
 * Argon2id key derivation per spec-1.md §A.1.
 *
 * Pure JVM (Bouncy Castle), zero Android imports, so it runs identically in
 * local unit tests. The 64-byte output is split into KEK [0:32] and
 * verifier [32:64].
 *
 * Escalation path (docs/security/key-architecture.md §9): if pure-JVM latency
 * becomes unacceptable, the engine can be swapped for native libsodium
 * `crypto_pwhash` behind this same contract (spec update required first).
 */
object Argon2Kdf {
    /** Total derived output length in bytes (KEK + verifier). */
    const val OUTPUT_BYTES = 64

    /** Salt length in bytes (random per user). */
    const val SALT_BYTES = 16

    /** KEK / verifier half-length in bytes. */
    const val HALF_BYTES = 32

    /**
     * Derives [OUTPUT_BYTES] bytes from [password] + [salt] with [params].
     * Deterministic for identical inputs.
     */
    fun derive(password: ByteArray, salt: ByteArray, params: KdfParams): ByteArray {
        require(salt.isNotEmpty()) { "salt must not be empty" }
        val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withMemoryAsKB(params.m)
            .withIterations(params.t)
            .withParallelism(params.p)
        val generator = Argon2BytesGenerator()
        generator.init(builder.build())
        val output = ByteArray(OUTPUT_BYTES)
        generator.generateBytes(password, output)
        return output
    }

    /** Convenience overload: UTF-8 encodes the chars, then zeroes both copies. */
    fun derive(password: CharArray, salt: ByteArray, params: KdfParams): ByteArray {
        val bytes = toBytes(password)
        try {
            return derive(bytes, salt, params)
        } finally {
            bytes.fill(0)
        }
    }

    /** Generates a fresh random 16-byte KDF salt. */
    fun randomSalt(): ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }

    /**
     * Splits the 64-byte derived output into KEK `[0:32]` and
     * verifier `[32:64]` (spec-1.md §A.1 steps 4–5). Returns fresh copies;
     * callers should zero every array they no longer need.
     */
    fun split(derived: ByteArray): Pair<ByteArray, ByteArray> {
        require(derived.size == OUTPUT_BYTES) {
            "derived output must be $OUTPUT_BYTES bytes, got ${derived.size}"
        }
        return derived.copyOfRange(0, HALF_BYTES) to derived.copyOfRange(HALF_BYTES, OUTPUT_BYTES)
    }

    /** UTF-8 encoding of a CharArray; the transient copy is zeroed by callers. */
    private fun toBytes(password: CharArray): ByteArray =
        String(password).toByteArray(StandardCharsets.UTF_8)
}
