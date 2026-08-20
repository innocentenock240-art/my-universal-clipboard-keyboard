package com.example.sync.transport

import com.example.data.model.ConnectionState
import com.example.data.model.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PeerSessionConnectionTest {

    @Test
    fun testSuccessfulPeerConnectionAndHandshake() = runBlocking {
        val receiverPort = 55401
        val receiver = LocalWifiTransport(port = receiverPort, customDeviceId = "dev_receiver_55401")
        receiver.startServer()
        assertTrue(receiver.isAvailable)

        val sender = LocalWifiTransport(port = 55402, customDeviceId = "dev_sender_55402")

        val targetDevice = Device(
            deviceId = receiver.localDeviceId,
            deviceName = receiver.localDeviceName,
            ipAddress = "127.0.0.1:$receiverPort",
            connectionState = ConnectionState.DISCOVERED
        )
        sender.addDiscoveredDevice(targetDevice)

        val success = sender.connectToDevice(targetDevice)
        assertTrue("Expected connection handshake to succeed", success)

        val connectedDev = sender.discoveredDevices.value.find { it.deviceId == receiver.localDeviceId }
        assertNotNull("Target device should exist in sender discovered list", connectedDev)
        assertEquals("Device connection state should be CONNECTED", ConnectionState.CONNECTED, connectedDev?.connectionState)

        sender.disconnectFromDevice(targetDevice.deviceId)
        receiver.stopServer()
        sender.stopServer()
    }

    @Test
    fun testMismatchedDeviceIdRejection() = runBlocking {
        val receiverPort = 55403
        val receiver = LocalWifiTransport(port = receiverPort, customDeviceId = "dev_receiver_55403")
        receiver.startServer()

        val sender = LocalWifiTransport(port = 55404, customDeviceId = "dev_sender_55404")

        // Target device expects "dev_fake_id", but receiver actually has receiver.localDeviceId
        val targetDeviceMismatched = Device(
            deviceId = "dev_fake_expected_id_123",
            deviceName = "Fake Target Phone",
            ipAddress = "127.0.0.1:$receiverPort",
            connectionState = ConnectionState.DISCOVERED
        )
        sender.addDiscoveredDevice(targetDeviceMismatched)

        val success = sender.connectToDevice(targetDeviceMismatched)
        assertFalse("Connection should be rejected due to deviceId mismatch", success)

        val devState = sender.discoveredDevices.value.find { it.deviceId == targetDeviceMismatched.deviceId }
        assertEquals("Mismatched device state should transition to ERROR", ConnectionState.ERROR, devState?.connectionState)

        receiver.stopServer()
        sender.stopServer()
    }

    @Test
    fun testDisconnectClosesSessionAndUpdateState() = runBlocking {
        val receiverPort = 55405
        val receiver = LocalWifiTransport(port = receiverPort, customDeviceId = "dev_receiver_55405")
        receiver.startServer()

        val sender = LocalWifiTransport(port = 55406, customDeviceId = "dev_sender_55406")

        val targetDevice = Device(
            deviceId = receiver.localDeviceId,
            deviceName = receiver.localDeviceName,
            ipAddress = "127.0.0.1:$receiverPort",
            connectionState = ConnectionState.DISCOVERED
        )
        sender.addDiscoveredDevice(targetDevice)

        sender.connectToDevice(targetDevice)
        assertEquals(ConnectionState.CONNECTED, sender.discoveredDevices.value.find { it.deviceId == targetDevice.deviceId }?.connectionState)

        sender.disconnectFromDevice(targetDevice.deviceId)
        assertEquals(ConnectionState.DISCONNECTED, sender.discoveredDevices.value.find { it.deviceId == targetDevice.deviceId }?.connectionState)

        receiver.stopServer()
        sender.stopServer()
    }

    @Test
    fun testDuplicateConnectionPrevented() = runBlocking {
        val receiverPort = 55407
        val receiver = LocalWifiTransport(port = receiverPort, customDeviceId = "dev_receiver_55407")
        receiver.startServer()

        val sender = LocalWifiTransport(port = 55408, customDeviceId = "dev_sender_55408")

        val targetDevice = Device(
            deviceId = receiver.localDeviceId,
            deviceName = receiver.localDeviceName,
            ipAddress = "127.0.0.1:$receiverPort",
            connectionState = ConnectionState.DISCOVERED
        )
        sender.addDiscoveredDevice(targetDevice)

        val firstAttempt = sender.connectToDevice(targetDevice)
        assertTrue(firstAttempt)

        val secondAttempt = sender.connectToDevice(targetDevice)
        assertTrue("Duplicate connect while already CONNECTED should return true without re-opening socket", secondAttempt)

        sender.disconnectFromDevice(targetDevice.deviceId)
        receiver.stopServer()
        sender.stopServer()
    }

    @Test
    fun testNoClipboardItemTransmittedDuringHandshake() = runBlocking {
        val receiverPort = 55409
        val receiver = LocalWifiTransport(port = receiverPort, customDeviceId = "dev_receiver_55409")
        receiver.startServer()

        val sender = LocalWifiTransport(port = 55410, customDeviceId = "dev_sender_55410")

        val targetDevice = Device(
            deviceId = receiver.localDeviceId,
            deviceName = receiver.localDeviceName,
            ipAddress = "127.0.0.1:$receiverPort"
        )
        sender.addDiscoveredDevice(targetDevice)

        val connectSuccess = sender.connectToDevice(targetDevice)
        assertTrue(connectSuccess)

        // Ensure no ClipboardItem is sent or received during connection establishment
        val itemReceived = withTimeoutOrNull(1000) {
            receiver.observeIncomingItems().first()
        }

        assertNull("Zero ClipboardItems should be transmitted during peer identity connection handshake", itemReceived)

        sender.disconnectFromDevice(targetDevice.deviceId)
        receiver.stopServer()
        sender.stopServer()
    }
}
