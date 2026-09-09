package com.veilkeepers.app.data

import com.veilkeepers.app.crypto.AuthHash
import com.veilkeepers.app.crypto.KdfParams
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Asserts the change-password and device-list JSON shapes match the frozen
 * backend contract (backend/internal/server/auth.go changePasswordRequest,
 * backend/internal/server/devices.go deviceDTO).
 */
class DeviceApiEncodingTest {

    private val base64Shape = Regex("^[A-Za-z0-9+/]+={0,2}$")

    private val currentHashB64 = AuthHash.toBase64(ByteArray(32) { it.toByte() })
    private val newHashB64 = AuthHash.toBase64(ByteArray(32) { (it + 10).toByte() })
    private val saltB64 = AuthHash.toBase64(ByteArray(16) { (it + 40).toByte() })
    private val wrappedB64 = AuthHash.toBase64(ByteArray(60) { (it + 80).toByte() })

    @Test
    fun changePasswordBodyFieldNamesMatchBackend() {
        val body = AuthPayloads.changePasswordBody(
            currentAuthHashB64 = currentHashB64,
            authHashB64 = newHashB64,
            kdfSaltB64 = saltB64,
            kdfParams = KdfParams.SPEC,
            wrappedVaultKeyB64 = wrappedB64,
        )

        val keys = mutableListOf<String>()
        body.keys().forEachRemaining { keys.add(it) }
        assertEquals(
            setOf("current_auth_hash", "auth_hash", "kdf_salt", "kdf_params", "wrapped_vault_key"),
            keys.toSet(),
        )
        assertEquals(5, keys.size)

        assertEquals(currentHashB64, body.getString("current_auth_hash"))
        assertTrue(base64Shape.matches(currentHashB64))
        assertEquals(newHashB64, body.getString("auth_hash"))
        assertTrue(base64Shape.matches(newHashB64))
        assertEquals(saltB64, body.getString("kdf_salt"))
        assertEquals(wrappedB64, body.getString("wrapped_vault_key"))

        val params = body.getJSONObject("kdf_params")
        assertEquals(65536, params.getInt("m"))
        assertEquals(3, params.getInt("t"))
        assertEquals(4, params.getInt("p"))
    }

    @Test
    fun deviceEntryParsesFromJsonObject() {
        val json = """[
            {"id":1,"device_identifier":"abc-123","device_name":"Pixel 7","created_at":"2026-09-01T12:00:00Z"},
            {"id":2,"device_identifier":"def-456","device_name":"","created_at":"2026-09-02T08:30:00Z"}
        ]"""
        val arr = JSONArray(json)
        val devices = (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            DeviceEntry(
                id = obj.getLong("id"),
                deviceIdentifier = obj.optString("device_identifier", ""),
                deviceName = obj.optString("device_name", ""),
                createdAt = obj.optString("created_at", ""),
            )
        }

        assertEquals(2, devices.size)
        assertEquals(1L, devices[0].id)
        assertEquals("abc-123", devices[0].deviceIdentifier)
        assertEquals("Pixel 7", devices[0].deviceName)
        assertEquals("2026-09-01T12:00:00Z", devices[0].createdAt)
        assertEquals(2L, devices[1].id)
        assertEquals("", devices[1].deviceName)
    }
}
