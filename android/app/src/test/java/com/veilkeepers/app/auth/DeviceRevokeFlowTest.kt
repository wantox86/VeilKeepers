package com.veilkeepers.app.auth

import com.veilkeepers.app.data.DeviceApi
import com.veilkeepers.app.data.DeviceEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class FakeDeviceApi : DeviceApi {
    var devices = mutableListOf<DeviceEntry>()
    var revokedIds = mutableListOf<Long>()
    var listError: Throwable? = null
    var revokeError: Throwable? = null

    override suspend fun listDevices(bearerToken: String): List<DeviceEntry> {
        listError?.let { throw it }
        return devices.toList()
    }

    override suspend fun revokeDevice(deviceId: Long, bearerToken: String) {
        revokeError?.let { throw it }
        revokedIds.add(deviceId)
        devices.removeAll { it.id == deviceId }
    }
}

class DeviceRevokeFlowTest {

    @Test
    fun listDevicesReturnsAllFromApi() = runBlocking {
        val api = FakeDeviceApi()
        api.devices.addAll(listOf(
            DeviceEntry(1L, "dev-a", "Phone", "2026-09-01"),
            DeviceEntry(2L, "dev-b", "Laptop", "2026-09-02"),
        ))

        val result = api.listDevices("token")
        assertEquals(2, result.size)
        assertEquals("Phone", result[0].deviceName)
        assertEquals("Laptop", result[1].deviceName)
    }

    @Test
    fun revokeDeviceRemovesFromList() = runBlocking {
        val api = FakeDeviceApi()
        api.devices.addAll(listOf(
            DeviceEntry(1L, "dev-a", "Phone", "2026-09-01"),
            DeviceEntry(2L, "dev-b", "Laptop", "2026-09-02"),
            DeviceEntry(3L, "dev-c", "Tablet", "2026-09-03"),
        ))

        api.revokeDevice(2L, "token")

        assertEquals(listOf(2L), api.revokedIds)
        assertEquals(2, api.devices.size)
        assertNull(api.devices.find { it.id == 2L })
    }
}
