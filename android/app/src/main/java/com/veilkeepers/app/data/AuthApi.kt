package com.veilkeepers.app.data

import com.veilkeepers.app.crypto.KdfParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/** KDF lookup result: base64 salt + stored parameters. */
data class KdfInfo(val saltB64: String, val params: KdfParams)

/** Successful login response (backend loginResponse, auth.go). */
data class LoginResult(
    val sessionToken: String,
    val wrappedVaultKeyB64: String,
    val expiresAt: String,
)

/**
 * Auth surface of the frozen backend contract (backend/internal/server/auth.go).
 * An interface so unit tests can inject fakes; [HttpAuthApi] is the real
 * implementation over [ApiClient].
 */
interface AuthApi {
    /** GET /api/v1/auth/kdf/{username} → { kdf_salt, kdf_params }. */
    suspend fun getKdf(username: String): KdfInfo

    /** POST /api/v1/auth/register. Throws [ApiError] on failure. */
    suspend fun register(
        username: String,
        authHashB64: String,
        kdfSaltB64: String,
        kdfParams: KdfParams,
        wrappedVaultKeyB64: String,
    )

    /** POST /api/v1/auth/login → session token + wrapped vault key + expiry. */
    suspend fun login(
        username: String,
        authHashB64: String,
        deviceIdentifier: String,
        deviceName: String,
    ): LoginResult

    /** POST /api/v1/auth/logout with the bearer token. */
    suspend fun logout(bearerToken: String)

    /** PUT /api/v1/auth/password — change password (spec-1 §A.1 re-wrap flow). */
    suspend fun changePassword(
        currentAuthHashB64: String,
        authHashB64: String,
        kdfSaltB64: String,
        kdfParams: KdfParams,
        wrappedVaultKeyB64: String,
        bearerToken: String,
    )
}

/**
 * JSON payload builders for the auth endpoints. Field names must match the
 * backend structs byte-for-byte; covered by ApiEncodingTest.
 */
object AuthPayloads {

    /** registerRequest: username / auth_hash / kdf_salt / kdf_params / wrapped_vault_key. */
    fun registerBody(
        username: String,
        authHashB64: String,
        kdfSaltB64: String,
        kdfParams: KdfParams,
        wrappedVaultKeyB64: String,
    ): JSONObject = JSONObject()
        .put("username", username)
        .put("auth_hash", authHashB64)
        .put("kdf_salt", kdfSaltB64)
        .put("kdf_params", JSONObject(kdfParams.encode()))
        .put("wrapped_vault_key", wrappedVaultKeyB64)

    /** loginRequest: username / auth_hash / device_identifier / device_name. */
    fun loginBody(
        username: String,
        authHashB64: String,
        deviceIdentifier: String,
        deviceName: String,
    ): JSONObject = JSONObject()
        .put("username", username)
        .put("auth_hash", authHashB64)
        .put("device_identifier", deviceIdentifier)
        .put("device_name", deviceName)

    /** changePasswordRequest: current_auth_hash / auth_hash / kdf_salt / kdf_params / wrapped_vault_key. */
    fun changePasswordBody(
        currentAuthHashB64: String,
        authHashB64: String,
        kdfSaltB64: String,
        kdfParams: KdfParams,
        wrappedVaultKeyB64: String,
    ): JSONObject = JSONObject()
        .put("current_auth_hash", currentAuthHashB64)
        .put("auth_hash", authHashB64)
        .put("kdf_salt", kdfSaltB64)
        .put("kdf_params", JSONObject(kdfParams.encode()))
        .put("wrapped_vault_key", wrappedVaultKeyB64)
}

/** [AuthApi] implementation backed by [ApiClient] (blocking I/O on Dispatchers.IO). */
class HttpAuthApi(private val client: ApiClient) : AuthApi {

    override suspend fun getKdf(username: String): KdfInfo = withContext(Dispatchers.IO) {
        val json = client.getJson("/api/v1/auth/kdf/" + encodeSegment(username))
        val params = json.optJSONObject("kdf_params")
            ?: throw ApiError.InvalidInput
        try {
            KdfInfo(
                saltB64 = json.optString("kdf_salt", ""),
                // parseFrom enforces the DoS ceilings (KdfParams.MAX_*); a
                // MITM'd absurd value is rejected as invalid input, never derived.
                params = KdfParams.parseFrom(params.toString()),
            )
        } catch (e: IllegalArgumentException) {
            throw ApiError.InvalidInput
        } catch (e: org.json.JSONException) {
            throw ApiError.InvalidInput
        }
    }

    override suspend fun register(
        username: String,
        authHashB64: String,
        kdfSaltB64: String,
        kdfParams: KdfParams,
        wrappedVaultKeyB64: String,
    ) {
        withContext(Dispatchers.IO) {
            client.postJson(
                "/api/v1/auth/register",
                AuthPayloads.registerBody(username, authHashB64, kdfSaltB64, kdfParams, wrappedVaultKeyB64),
            )
        }
    }

    override suspend fun login(
        username: String,
        authHashB64: String,
        deviceIdentifier: String,
        deviceName: String,
    ): LoginResult = withContext(Dispatchers.IO) {
        val json = client.postJson(
            "/api/v1/auth/login",
            AuthPayloads.loginBody(username, authHashB64, deviceIdentifier, deviceName),
        )
        val result = LoginResult(
            sessionToken = json.optString("session_token", ""),
            wrappedVaultKeyB64 = json.optString("wrapped_vault_key", ""),
            expiresAt = json.optString("expires_at", ""),
        )
        // A success body missing either field is a broken/tampered server —
        // never persist or unwrap partial state.
        if (result.sessionToken.isEmpty() || result.wrappedVaultKeyB64.isEmpty()) {
            throw ApiError.Internal
        }
        result
    }

    override suspend fun logout(bearerToken: String) {
        withContext(Dispatchers.IO) {
            client.postJson("/api/v1/auth/logout", JSONObject(), bearerToken = bearerToken)
        }
    }

    override suspend fun changePassword(
        currentAuthHashB64: String,
        authHashB64: String,
        kdfSaltB64: String,
        kdfParams: KdfParams,
        wrappedVaultKeyB64: String,
        bearerToken: String,
    ) {
        withContext(Dispatchers.IO) {
            client.putJson(
                "/api/v1/auth/password",
                AuthPayloads.changePasswordBody(
                    currentAuthHashB64, authHashB64, kdfSaltB64, kdfParams, wrappedVaultKeyB64,
                ),
                bearerToken = bearerToken,
            )
        }
    }

    private fun encodeSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

/** One row of GET /api/v1/devices (backend devices.go deviceDTO). */
data class DeviceEntry(
    val id: Long,
    val deviceIdentifier: String,
    val deviceName: String,
    val createdAt: String,
)

/**
 * Device surface of the backend contract (backend/internal/server/devices.go).
 * An interface so unit tests can inject fakes.
 */
interface DeviceApi {
    /** GET /api/v1/devices → list of the caller's devices. */
    suspend fun listDevices(bearerToken: String): List<DeviceEntry>

    /** DELETE /api/v1/devices/{id} → revoke a device and its sessions. */
    suspend fun revokeDevice(deviceId: Long, bearerToken: String)
}

/** [DeviceApi] implementation backed by [ApiClient]. */
class HttpDeviceApi(private val client: ApiClient) : DeviceApi {

    override suspend fun listDevices(bearerToken: String): List<DeviceEntry> =
        withContext(Dispatchers.IO) {
            val arr = client.getJsonArray("/api/v1/devices", bearerToken = bearerToken)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                DeviceEntry(
                    id = obj.getLong("id"),
                    deviceIdentifier = obj.optString("device_identifier", ""),
                    deviceName = obj.optString("device_name", ""),
                    createdAt = obj.optString("created_at", ""),
                )
            }
        }

    override suspend fun revokeDevice(deviceId: Long, bearerToken: String) {
        withContext(Dispatchers.IO) {
            client.deleteJson("/api/v1/devices/$deviceId", bearerToken = bearerToken)
        }
    }
}
