package com.veilkeepers.app.data

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal JSON-over-HTTP client on top of [HttpURLConnection] — the backend
 * contract is frozen in backend/internal/server/auth.go.
 *
 * Success bodies are returned as [JSONObject]; non-2xx bodies are parsed as
 * the backend's `{"error":code,"message":...}` envelope (respond.go) and
 * thrown as the matching [ApiError]. Transport problems become [ApiError.Network].
 *
 * Intentionally blocking — call only from a worker thread (Dispatchers.IO).
 * No request/response bodies or tokens are ever logged (spec-1.md §G.6).
 */
class ApiClient(
    @Volatile var baseUrl: String,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
) {

    /** GET [path] and parse the 2xx body as JSON. */
    fun getJson(path: String, bearerToken: String? = null): JSONObject =
        execute("GET", path, body = null, bearerToken = bearerToken)

    /** POST [body] to [path] and parse the 2xx response body as JSON. */
    fun postJson(path: String, body: JSONObject, bearerToken: String? = null): JSONObject =
        execute("POST", path, body = body.toString(), bearerToken = bearerToken)

    /** PUT [body] to [path] and parse the 2xx response body as JSON. */
    fun putJson(path: String, body: JSONObject, bearerToken: String? = null): JSONObject =
        execute("PUT", path, body = body.toString(), bearerToken = bearerToken)

    /** DELETE [path] (no request body) and parse the 2xx response body as JSON. */
    fun deleteJson(path: String, bearerToken: String? = null): JSONObject =
        execute("DELETE", path, body = null, bearerToken = bearerToken)

    private fun execute(method: String, path: String, body: String?, bearerToken: String?): JSONObject {
        val root = baseUrl.trim().trimEnd('/')
        val connection: HttpURLConnection = try {
            val conn = URL(root + path).openConnection() as HttpURLConnection
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Accept", "application/json")
            bearerToken?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            conn
        } catch (e: IOException) {
            throw ApiError.Network(e)
        } catch (e: ClassCastException) {
            throw ApiError.InvalidInput
        }

        try {
            if (body != null) {
                connection.requestMethod = method
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            } else {
                connection.requestMethod = method
            }

            val status = connection.responseCode
            if (status in 200..299) {
                val text = connection.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
                return try {
                    JSONObject(text)
                } catch (e: JSONException) {
                    throw ApiError.Internal
                }
            }
            throw errorFrom(status, connection)
        } catch (e: ApiError) {
            throw e
        } catch (e: IOException) {
            throw ApiError.Network(e)
        } finally {
            connection.disconnect()
        }
    }

    /** Parses the `{"error":code,...}` envelope; unknown shapes map to [ApiError.Internal]. */
    private fun errorFrom(status: Int, connection: HttpURLConnection): ApiError {
        val text = try {
            connection.errorStream?.use { it.readBytes() }?.toString(Charsets.UTF_8) ?: ""
        } catch (e: IOException) {
            ""
        }
        val code = try {
            JSONObject(text).optString("error", "")
        } catch (e: JSONException) {
            ""
        }
        if (code.isEmpty()) return ApiError.Internal
        return ApiError.fromCode(code)
    }

    companion object {
        /** Connect timeout: LAN-only server, fail fast. */
        const val CONNECT_TIMEOUT_MS = 5_000

        /** Read timeout: sized for server-side bcrypt, not for client KDF. */
        const val READ_TIMEOUT_MS = 20_000
    }
}
