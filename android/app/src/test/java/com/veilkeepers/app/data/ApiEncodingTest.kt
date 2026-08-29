package com.veilkeepers.app.data

import com.veilkeepers.app.crypto.AuthHash
import com.veilkeepers.app.crypto.KdfParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Asserts the JSON payloads match the frozen backend contract
 * (backend/internal/server/auth.go structs) field-for-field, and that all
 * binary fields use standard base64 with backend-compatible shapes.
 */
class ApiEncodingTest {

    private val base64Shape = Regex("^[A-Za-z0-9+/]+={0,2}$")

    private val authHashB64 = AuthHash.toBase64(ByteArray(32) { it.toByte() })
    private val saltB64 = AuthHash.toBase64(ByteArray(16) { (it + 40).toByte() })
    private val wrappedB64 = AuthHash.toBase64(ByteArray(60) { (it + 80).toByte() })

    @Test
    fun registerBodyFieldNamesMatchBackend() {
        val body = AuthPayloads.registerBody(
            username = "alice",
            authHashB64 = authHashB64,
            kdfSaltB64 = saltB64,
            kdfParams = KdfParams.SPEC,
            wrappedVaultKeyB64 = wrappedB64,
        )

        val keys = mutableListOf<String>()
        body.keys().forEachRemaining { keys.add(it) }
        assertEquals(
            setOf("username", "auth_hash", "kdf_salt", "kdf_params", "wrapped_vault_key"),
            keys.toSet(),
        )
        assertEquals(5, keys.size)

        assertEquals("alice", body.getString("username"))
        assertEquals(authHashB64, body.getString("auth_hash"))
        assertEquals(saltB64, body.getString("kdf_salt"))
        assertEquals(wrappedB64, body.getString("wrapped_vault_key"))

        // kdf_params is a nested JSON object with exactly m, t, p.
        val params = body.getJSONObject("kdf_params")
        val paramKeys = mutableListOf<String>()
        params.keys().forEachRemaining { paramKeys.add(it) }
        assertEquals(setOf("m", "t", "p"), paramKeys.toSet())
        assertEquals(65536, params.getInt("m"))
        assertEquals(3, params.getInt("t"))
        assertEquals(4, params.getInt("p"))
    }

    @Test
    fun kdfParamsCanonicalEncoding() {
        assertEquals("""{"m":65536,"t":3,"p":4}""", KdfParams.SPEC.encode())
        assertEquals("""{"m":65536,"t":3,"p":4}""", KdfParams.SPEC.toJsonObject().let {
            // Order-free semantic equality with the canonical string.
            KdfParams.parseFrom(it.toString()).encode()
        })
        val parsed = KdfParams.parseFrom("""{"m":65536,"t":3,"p":4}""")
        assertEquals(KdfParams.SPEC, parsed)
    }

    @Test
    fun loginBodyFieldNamesMatchBackend() {
        val body = AuthPayloads.loginBody(
            username = "alice",
            authHashB64 = authHashB64,
            deviceIdentifier = "0f9c1c2e-9d3a-4b8e-8f2a-6d1c9e5b3a70",
            deviceName = "Pixel 8",
        )

        val keys = mutableListOf<String>()
        body.keys().forEachRemaining { keys.add(it) }
        assertEquals(
            setOf("username", "auth_hash", "device_identifier", "device_name"),
            keys.toSet(),
        )
        assertEquals(4, keys.size)

        assertEquals("alice", body.getString("username"))
        assertEquals(authHashB64, body.getString("auth_hash"))
        assertEquals("0f9c1c2e-9d3a-4b8e-8f2a-6d1c9e5b3a70", body.getString("device_identifier"))
        assertEquals("Pixel 8", body.getString("device_name"))
    }

    @Test
    fun kdfParamsClampsRejectAbsurdServerValues() {
        // Absurd server-supplied params (e.g. MITM'd kdf response) must be
        // rejected BEFORE any derivation happens — DoS clamp (KdfParams.MAX_*).
        for (json in listOf(
            """{"m":${Int.MAX_VALUE},"t":3,"p":4}""",
            """{"m":65536,"t":1000000000,"p":4}""",
            """{"m":65536,"t":3,"p":1000000000}""",
        )) {
            try {
                KdfParams.parseFrom(json)
                fail("expected rejection of $json")
            } catch (expected: IllegalArgumentException) {
                // expected — above the m/t/p ceilings
            }
        }
        // Ceiling values are accepted...
        assertEquals(
            KdfParams(KdfParams.MAX_M_KIB, KdfParams.MAX_T, KdfParams.MAX_P),
            KdfParams.parseFrom("""{"m":1048576,"t":16,"p":16}"""),
        )
        // ...and the frozen spec params are accepted.
        assertEquals(KdfParams.SPEC, KdfParams.parseFrom("""{"m":65536,"t":3,"p":4}"""))
    }

    @Test
    fun base64ShapesMatchBackendByteLimits() {
        // auth_hash: exactly 32 bytes (backend authHashBytes).
        assertTrue(authHashB64.matches(base64Shape))
        assertEquals(32, AuthHash.fromBase64(authHashB64).size)

        // kdf_salt: 16 bytes (backend requires ≥16, ≤32).
        assertTrue(saltB64.matches(base64Shape))
        assertEquals(16, AuthHash.fromBase64(saltB64).size)

        // wrapped_vault_key: 60 bytes nonce||ciphertext (backend allows ≤128).
        assertTrue(wrappedB64.matches(base64Shape))
        assertEquals(60, AuthHash.fromBase64(wrappedB64).size)
    }
}
