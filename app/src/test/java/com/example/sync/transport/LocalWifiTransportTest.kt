package com.example.sync.transport

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalWifiTransportTest {

    @Test
    fun testStartAndStopServer() {
        val transport = LocalWifiTransport(port = 54321)
        assertFalse(transport.isAvailable)

        transport.startServer()
        assertTrue(transport.isAvailable)

        transport.stopServer()
        assertFalse(transport.isAvailable)
    }

    @Test
    fun testHandshakeCommunication() = runBlocking {
        val transportReceiver = LocalWifiTransport(port = 54322)
        transportReceiver.startServer()
        assertTrue(transportReceiver.isAvailable)

        val transportSender = LocalWifiTransport(port = 54323)

        val ackResponse = transportSender.sendHandshake(
            targetIp = "127.0.0.1",
            targetPort = 54322,
            message = "HELLO_FROM_PHONE_A"
        )

        assertNotNull(ackResponse)
        assertTrue(ackResponse!!.startsWith("ACK_"))

        val receivedMsg = withTimeoutOrNull(2000) {
            transportReceiver.incomingMessages.first()
        }

        assertEquals("HELLO_FROM_PHONE_A", receivedMsg)

        transportReceiver.stopServer()
        transportSender.stopServer()
    }

    @Test
    fun testUniquePersistentLocalDeviceIdWithContext() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val transport1 = LocalWifiTransport(context = context, port = 54325)
        val id1 = transport1.localDeviceId

        val transport2 = LocalWifiTransport(context = context, port = 54326)
        val id2 = transport2.localDeviceId

        // Same app instance context should retrieve the same persistent unique ID from SharedPreferences
        assertEquals(id1, id2)
        assertTrue(id1.startsWith("uclip_dev_") || id1.startsWith("dev_"))
    }

    @Test
    fun testAddDiscoveredDeviceSelfFilteringAndDuplicatePrevention() = runBlocking {
        val transport = LocalWifiTransport(port = 54324)

        // 1. Try adding local device ID -> should be filtered out
        val localDev = com.example.data.model.Device(
            deviceId = transport.localDeviceId,
            deviceName = transport.localDeviceName,
            ipAddress = "192.168.1.100"
        )
        transport.addDiscoveredDevice(localDev)
        assertTrue(transport.discoveredDevices.value.isEmpty())

        // 2. Add remote device Phone B
        val remoteDevB = com.example.data.model.Device(
            deviceId = "dev_phone_b",
            deviceName = "Phone B",
            ipAddress = "192.168.1.101"
        )
        transport.addDiscoveredDevice(remoteDevB)
        assertEquals(1, transport.discoveredDevices.value.size)
        assertEquals("Phone B", transport.discoveredDevices.value[0].deviceName)
        assertEquals("192.168.1.101", transport.discoveredDevices.value[0].ipAddress)

        // 3. Add remote device Phone B again with updated IP -> should update without creating duplicate
        val remoteDevBUpdated = com.example.data.model.Device(
            deviceId = "dev_phone_b",
            deviceName = "Phone B",
            ipAddress = "192.168.1.105"
        )
        transport.addDiscoveredDevice(remoteDevBUpdated)
        assertEquals(1, transport.discoveredDevices.value.size)
        assertEquals("192.168.1.105", transport.discoveredDevices.value[0].ipAddress)

        // 4. Clear discovered devices
        transport.clearDiscoveredDevices()
        assertTrue(transport.discoveredDevices.value.isEmpty())
    }
}

