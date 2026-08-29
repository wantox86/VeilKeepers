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
}

/** [AuthApi] implementation backed by [ApiClient] (blocking I/O on Dispatchers.IO). */
class HttpAuthApi(private val client: ApiClient) : AuthApi {

    override suspend fun getKdf(username: String): KdfInfo = withContext(Dispatchers.IO) {
        val json = client.getJson("/api/v1/auth/kdf/" + encodeSegment(username))
        val params = json.optJSONObject("kdf_params")
            ?: throw ApiError.Internal
        KdfInfo(
            saltB64 = json.optString("kdf_salt", ""),
            params = KdfParams.parseFrom(params.toString()),
        )
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
        LoginResult(
            sessionToken = json.optString("session_token", ""),
            wrappedVaultKeyB64 = json.optString("wrapped_vault_key", ""),
            expiresAt = json.optString("expires_at", ""),
        )
    }

    override suspend fun logout(bearerToken: String) {
        withContext(Dispatchers.IO) {
            client.postJson("/api/v1/auth/logout", JSONObject(), bearerToken = bearerToken)
        }
    }

    private fun encodeSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
