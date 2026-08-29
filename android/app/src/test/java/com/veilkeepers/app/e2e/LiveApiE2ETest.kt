package com.veilkeepers.app.e2e

import com.veilkeepers.app.crypto.Argon2Kdf
import com.veilkeepers.app.crypto.AuthHash
import com.veilkeepers.app.crypto.KdfParams
import com.veilkeepers.app.crypto.VaultKey
import com.veilkeepers.app.data.ApiClient
import com.veilkeepers.app.data.HttpAuthApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/**
 * Live end-to-end test against a running VeilKeepers backend.
 *
 * SKIPPED unless VK_E2E_BASE_URL is set, e.g.:
 *   VK_E2E_BASE_URL=http://192.168.50.131:18080 ./gradlew :app:testDebugUnitTest
 *
 * One run performs exactly 4 auth calls (register, kdf, login, logout) —
 * comfortably inside the 10 req/min per-IP budget — plus a /health sanity
 * GET. No retry loops: a rate-limited run is a failed run.
 */
class LiveApiE2ETest {

    @Test(timeout = 600_000)
    fun fullAuthCycleAgainstLiveBackend() {
        val baseUrl = System.getenv("VK_E2E_BASE_URL")
        assumeTrue("VK_E2E_BASE_URL not set — skipping live E2E test", baseUrl != null)

        runBlocking {
            val api = HttpAuthApi(ApiClient(baseUrl!!))
            val username = "vk" + System.currentTimeMillis()
            val password = "veil-e2e-${UUID.randomUUID()}".toCharArray()

            // Register: derive with the frozen spec params, generate + wrap VK.
            val salt = Argon2Kdf.randomSalt()
            val derived = Argon2Kdf.derive(password, salt, KdfParams.SPEC)
            val (kek, verifier) = Argon2Kdf.split(derived)
            val authHashB64 = AuthHash.toBase64(AuthHash.of(verifier))
            val vaultKey = VaultKey.generate()
            val wrapped = VaultKey.wrap(vaultKey, kek)

            api.register(
                username = username,
                authHashB64 = authHashB64,
                kdfSaltB64 = AuthHash.toBase64(salt),
                kdfParams = KdfParams.SPEC,
                wrappedVaultKeyB64 = AuthHash.toBase64(wrapped),
            )

            // KDF lookup must echo the salt and params we registered with.
            val info = api.getKdf(username)
            assertEquals(AuthHash.toBase64(salt), info.saltB64)
            assertEquals(KdfParams.SPEC, info.params)

            // Login with a fresh derivation from the server-provided salt.
            val derived2 = Argon2Kdf.derive(password, AuthHash.fromBase64(info.saltB64), info.params)
            val (kek2, verifier2) = Argon2Kdf.split(derived2)
            assertArrayEquals(AuthHash.of(verifier), AuthHash.of(verifier2))

            val login = api.login(
                username = username,
                authHashB64 = AuthHash.toBase64(AuthHash.of(verifier2)),
                deviceIdentifier = UUID.randomUUID().toString(),
                deviceName = "jvm-e2e",
            )
            assertTrue(login.sessionToken.isNotEmpty())
            assertTrue(login.expiresAt.isNotEmpty())

            // The server's wrapped blob must unwrap to the VK we generated.
            val unwrapped = VaultKey.unwrap(AuthHash.fromBase64(login.wrappedVaultKeyB64), kek2)
            assertArrayEquals(vaultKey, unwrapped)

            // GET-ish sanity: liveness probe.
            val health = ApiClient(baseUrl).getJson("/health")
            assertEquals("ok", health.optString("status"))

            // Logout revokes the session.
            api.logout(login.sessionToken)

            // Belt-and-braces: the session token must no longer authorize.
            try {
                ApiClient(baseUrl).postJson(
                    "/api/v1/auth/logout",
                    org.json.JSONObject(),
                    bearerToken = login.sessionToken,
                )
                throw AssertionError("revoked session token still authorizes logout")
            } catch (expected: com.veilkeepers.app.data.ApiError) {
                assertNotNull(expected)
            }
        }
    }
}
