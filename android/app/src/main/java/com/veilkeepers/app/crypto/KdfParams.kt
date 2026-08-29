package com.veilkeepers.app.crypto

import org.json.JSONObject

/**
 * Argon2id KDF parameters, stored per user on the server as the JSON object
 * `{"m":...,"t":...,"p":...}` (spec-1.md §A.1).
 *
 * The frozen production values are m=65536 KiB, t=3, p=4. Changing them is a
 * security decision and requires a spec document update first (spec.md §56
 * Rule 2 / spec-1.md §G.2).
 */
data class KdfParams(
    /** Memory cost in KiB (`m`). */
    val m: Int,
    /** Iterations / time cost (`t`). */
    val t: Int,
    /** Parallelism (`p`). */
    val p: Int,
) {
    init {
        require(m > 0) { "kdf_params.m must be a positive integer" }
        require(t > 0) { "kdf_params.t must be a positive integer" }
        require(p > 0) { "kdf_params.p must be a positive integer" }
        // DoS clamps for SERVER-supplied params (login flow): an attacker or
        // a MITM must not be able to force multi-GiB / hour-long derivations.
        require(m <= MAX_M_KIB) { "kdf_params.m exceeds the ${MAX_M_KIB} KiB ceiling" }
        require(t <= MAX_T) { "kdf_params.t exceeds the $MAX_T ceiling" }
        require(p <= MAX_P) { "kdf_params.p exceeds the $MAX_P ceiling" }
    }

    /**
     * JSON object form for embedding into request/response bodies.
     * Note: field order on the wire is not semantically significant; the
     * backend only requires `m`, `t`, `p` to be present positive integers.
     */
    fun toJsonObject(): JSONObject = JSONObject()
        .put("m", m)
        .put("t", t)
        .put("p", p)

    /**
     * Canonical deterministic encoding used in tests and anywhere a stable
     * byte representation matters: exactly `{"m":65536,"t":3,"p":4}`-shaped.
     */
    fun encode(): String = "{\"m\":$m,\"t\":$t,\"p\":$p}"

    override fun toString(): String = encode()

    companion object {
        /** Frozen production parameters (spec-1.md §A.1). */
        val SPEC = KdfParams(m = 65536, t = 3, p = 4)

        /** DoS ceilings for server-supplied params: 1 GiB memory, t/p ≤ 16. */
        const val MAX_M_KIB = 1_048_576
        const val MAX_T = 16
        const val MAX_P = 16

        /**
         * Parses `{"m":..,"t":..,"p":..}`; rejects missing/invalid fields and
         * values above the DoS ceilings (throws IllegalArgumentException).
         */
        fun parseFrom(json: String): KdfParams {
            val obj = JSONObject(json)
            return KdfParams(
                m = obj.getInt("m"),
                t = obj.getInt("t"),
                p = obj.getInt("p"),
            )
        }
    }
}
