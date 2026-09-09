package com.veilkeepers.app.e2e

import com.veilkeepers.app.crypto.Argon2Kdf
import com.veilkeepers.app.crypto.AuthHash
import com.veilkeepers.app.crypto.KdfParams
import com.veilkeepers.app.crypto.VaultKey
import com.veilkeepers.app.data.ApiClient
import com.veilkeepers.app.data.ApiError
import com.veilkeepers.app.data.HttpAuthApi
import com.veilkeepers.app.data.HttpDeviceApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/**
 * Live end-to-end test for Sprint 11 account management against a running
 * VeilKeepers backend.
 *
 * SKIPPED unless VK_E2E_BASE_URL is set:
 *   VK_E2E_BASE_URL=http://192.168.50.131:18080 ./gradlew :app:testDebugUnitTest
 *
 * Flow: register → login A + B → change password from A → B is revoked →
 * A is still alive → new password login works → vault items still decrypt
 * → device list → revoke device.
 */
class LiveAccountE2ETest {

    @Test(timeout = 600_000)
    fun changePasswordAndDevicesCycle() {
        val baseUrl = System.getenv("VK_E2E_BASE_URL")
        assumeTrue("VK_E2E_BASE_URL not set — skipping live E2E test", baseUrl != null)

        runBlocking {
            val api = HttpAuthApi(ApiClient(baseUrl!!))
            val deviceApi = HttpDeviceApi(ApiClient(baseUrl))
            val username = "vk11" + System.currentTimeMillis()
            val password = "veil-old-${UUID.randomUUID()}".toCharArray()
            val newPassword = "veil-new-${UUID.randomUUID()}".toCharArray()

            // Register.
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

            // Login device A.
            val loginA = api.login(
                username = username,
                authHashB64 = authHashB64,
                deviceIdentifier = "e2e-device-a-" + UUID.randomUUID(),
                deviceName = "E2E Device A",
            )
            assertTrue(loginA.sessionToken.isNotEmpty())

            // Login device B.
            val loginB = api.login(
                username = username,
                authHashB64 = authHashB64,
                deviceIdentifier = "e2e-device-b-" + UUID.randomUUID(),
                deviceName = "E2E Device B",
            )
            assertTrue(loginB.sessionToken.isNotEmpty())

            // Verify B's token is alive before the change.
            deviceApi.listDevices(loginB.sessionToken)

            // Change password from device A.
            val newSalt = Argon2Kdf.randomSalt()
            val newDerived = Argon2Kdf.derive(newPassword, newSalt, KdfParams.SPEC)
            val (newKek, newVerifier) = Argon2Kdf.split(newDerived)
            val newAuthHashB64 = AuthHash.toBase64(AuthHash.of(newVerifier))
            val newWrapped = VaultKey.wrap(vaultKey, newKek)

            api.changePassword(
                currentAuthHashB64 = authHashB64,
                authHashB64 = newAuthHashB64,
                kdfSaltB64 = AuthHash.toBase64(newSalt),
                kdfParams = KdfParams.SPEC,
                wrappedVaultKeyB64 = AuthHash.toBase64(newWrapped),
                bearerToken = loginA.sessionToken,
            )

            // Device A's token must still be alive.
            val devicesAfterChange = deviceApi.listDevices(loginA.sessionToken)
            assertTrue("At least one device (A) must remain", devicesAfterChange.isNotEmpty())

            // Device B's token must be revoked (401 on any authenticated call).
            try {
                deviceApi.listDevices(loginB.sessionToken)
                fail("Device B should have been revoked after password change")
            } catch (expected: ApiError.SessionExpired) {
                // expected
            }

            // New password login must work.
            val loginNew = api.login(
                username = username,
                authHashB64 = newAuthHashB64,
                deviceIdentifier = "e2e-device-c-" + UUID.randomUUID(),
                deviceName = "E2E Device C",
            )
            assertTrue(loginNew.sessionToken.isNotEmpty())

            // The wrapped VK from new-password login must decrypt to the same VK.
            val unwrappedNew = VaultKey.unwrap(
                AuthHash.fromBase64(loginNew.wrappedVaultKeyB64),
                newKek,
            )
            assertArrayEquals(vaultKey, unwrappedNew)

            // Old password login must fail.
            try {
                api.login(
                    username = username,
                    authHashB64 = authHashB64,
                    deviceIdentifier = "e2e-device-d-" + UUID.randomUUID(),
                    deviceName = "E2E Device D",
                )
                fail("Old password should fail after change")
            } catch (expected: ApiError.InvalidCredentials) {
                // expected
            }

            // Device list from the new login must show active sessions.
            val finalDevices = deviceApi.listDevices(loginNew.sessionToken)
            assertTrue("At least one device must be active", finalDevices.isNotEmpty())
            assertTrue(finalDevices.any { it.deviceIdentifier.startsWith("e2e-device-") })
        }
    }
}
