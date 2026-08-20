package com.example.sync.transport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.ConnectionState
import com.example.data.model.Device
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.example.core.identity.DeviceTrustManager

@RunWith(RobolectricTestRunner::class)
class AutoReconnectAndPresenceTest {

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        DeviceTrustManager.clearAllTrustedPeers(context)
        DeviceTrustManager.resetCacheForTesting(context)
    }

    @After
    fun tearDown() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        DeviceTrustManager.clearAllTrustedPeers(context)
    }

    @Test
    fun testUnknownDeviceDoesNotAutoConnect() = runBlocking {
        val receiverPort = 55501
        val receiver = LocalWifiTransport(port = receiverPort, customDeviceId = "dev_receiver_unknown")
        receiver.startServer()

        val sender = LocalWifiTransport(port = 55502, customDeviceId = "dev_sender_55502")

        // Unknown device discovered
        val unknownDevice = Device(
            deviceId = receiver.localDeviceId,
            deviceName = receiver.localDeviceName,
            ipAddress = "127.0.0.1:$receiverPort",
            connectionState = ConnectionState.DISCOVERED
        )

        sender.addDiscoveredDevice(unknownDevice)
        delay(300)

        // Must NOT be connected automatically
        val deviceInList = sender.discoveredDevices.value.find { it.deviceId == receiver.localDeviceId }
        assertNotNull(deviceInList)
        assertEquals(ConnectionState.DISCOVERED, deviceInList?.connectionState)
        assertFalse("Unknown device should not be paired/known", sender.isKnownPeer(receiver.localDeviceId))

        receiver.stopServer()
        sender.stopServer()
    }

    @Test
    fun testKnownPeerAutoReconnectsUponRediscovery() = runBlocking {
        val receiverPort = 55503
        val receiver = LocalWifiTransport(port = receiverPort, customDeviceId = "dev_receiver_known_55503")
        receiver.startServer()

        val sender = LocalWifiTransport(port = 55504, customDeviceId = "dev_sender_55504")

        val targetDevice = Device(
            deviceId = receiver.localDeviceId,
            deviceName = receiver.localDeviceName,
            ipAddress = "127.0.0.1:$receiverPort",
            connectionState = ConnectionState.DISCOVERED
        )

        // 1. Initial manual connection by user
        sender.addDiscoveredDevice(targetDevice)
        val initialConnect = sender.connectToDevice(targetDevice)
        assertTrue("Initial connection should succeed", initialConnect)
        assertTrue("Peer should now be known", sender.isKnownPeer(receiver.localDeviceId))
        assertEquals(ConnectionState.CONNECTED, sender.discoveredDevices.value.find { it.deviceId == receiver.localDeviceId }?.connectionState)

        // 2. Disconnect happens (e.g. Wi-Fi toggle or socket drop)
        sender.disconnectFromDevice(receiver.localDeviceId)
        assertEquals(ConnectionState.DISCONNECTED, sender.discoveredDevices.value.find { it.deviceId == receiver.localDeviceId }?.connectionState)
        assertTrue("Peer must still remain known after disconnect", sender.isKnownPeer(receiver.localDeviceId))

        // 3. Rediscovery event (NSD reports the peer again)
        val rediscoveredDevice = Device(
            deviceId = receiver.localDeviceId,
            deviceName = receiver.localDeviceName,
            ipAddress = "127.0.0.1:$receiverPort",
            connectionState = ConnectionState.DISCOVERED
        )
        sender.addDiscoveredDevice(rediscoveredDevice)

        // Allow auto-reconnection loop to complete
        var connected = false
        for (i in 1..20) {
            delay(100)
            if (sender.discoveredDevices.value.find { it.deviceId == receiver.localDeviceId }?.connectionState == ConnectionState.CONNECTED) {
                connected = true
                break
            }
        }

        assertTrue("Known peer should have automatically reconnected upon rediscovery", connected)

        sender.disconnectFromDevice(receiver.localDeviceId)
        receiver.stopServer()
        sender.stopServer()
    }

    @Test
    fun testAutoReconnectWithChangedIpAddress() = runBlocking {
        // Peer starts on first port/IP
        val receiverPort1 = 55505
        val receiver1 = LocalWifiTransport(port = receiverPort1, customDeviceId = "dev_peer_moving_ip")
        receiver1.startServer()

        val sender = LocalWifiTransport(port = 55506, customDeviceId = "dev_sender_55506")

        val targetDevice1 = Device(
            deviceId = receiver1.localDeviceId,
            deviceName = "Phone B",
            ipAddress = "127.0.0.1:$receiverPort1"
        )
        sender.addDiscoveredDevice(targetDevice1)
        val firstConnect = sender.connectToDevice(targetDevice1)
        assertTrue(firstConnect)

        // Disconnect first session and stop first receiver
        sender.disconnectFromDevice(receiver1.localDeviceId)
        receiver1.stopServer()

        // Receiver starts on new port (simulating new IP / address change)
        val receiverPort2 = 55507
        val receiver2 = LocalWifiTransport(port = receiverPort2, customDeviceId = "dev_peer_moving_ip")
        receiver2.startServer()

        // NSD discovers the same deviceId on new IP/port
        val targetDeviceNewIp = Device(
            deviceId = receiver2.localDeviceId,
            deviceName = "Phone B",
            ipAddress = "127.0.0.1:$receiverPort2"
        )
        sender.addDiscoveredDevice(targetDeviceNewIp)

        // Wait for automatic reconnection to new address
        var reconnected = false
        for (i in 1..20) {
            delay(100)
            val dev = sender.discoveredDevices.value.find { it.deviceId == "dev_peer_moving_ip" }
            if (dev?.connectionState == ConnectionState.CONNECTED && dev.ipAddress == "127.0.0.1:$receiverPort2") {
                reconnected = true
                break
            }
        }

        assertTrue("Known peer must auto-reconnect to updated IP address", reconnected)

        sender.disconnectFromDevice("dev_peer_moving_ip")
        receiver2.stopServer()
        sender.stopServer()
    }

    @Test
    fun testKnownPeersPersistAcrossAppRestartsViaPreferences() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Instance 1: Connects to peer and persists
        val transport1 = LocalWifiTransport(context = context, port = 55508, customDeviceId = "dev_app_inst_1")
        transport1.addKnownPeer("dev_remote_peer_persistent_123")
        assertTrue(transport1.isKnownPeer("dev_remote_peer_persistent_123"))

        // Instance 2: Represents app restart with same persistent storage
        val transport2 = LocalWifiTransport(context = context, port = 55509, customDeviceId = "dev_app_inst_2")
        assertTrue("Restored app instance should load known peers from SharedPreferences", transport2.isKnownPeer("dev_remote_peer_persistent_123"))
        assertEquals(setOf("dev_remote_peer_persistent_123"), transport2.getKnownPeers())

        transport1.clearKnownPeers()
    }

    @Test
    fun testReconnectionBackoffTerminatesOnUnreachableHost() = runBlocking {
        val sender = LocalWifiTransport(port = 55510, customDeviceId = "dev_sender_55510")
        sender.addKnownPeer("dev_offline_peer")

        // Unreachable device
        val offlineDevice = Device(
            deviceId = "dev_offline_peer",
            deviceName = "Offline Phone",
            ipAddress = "127.0.0.1:59999", // closed port
            connectionState = ConnectionState.DISCOVERED
        )

        sender.addDiscoveredDevice(offlineDevice)

        // Wait for retry loop (max 3 attempts) to finish
        var isDisconnected = false
        for (i in 1..60) {
            delay(100)
            val state = sender.discoveredDevices.value.find { it.deviceId == "dev_offline_peer" }?.connectionState
            if (state == ConnectionState.DISCONNECTED) {
                isDisconnected = true
                break
            }
        }

        assertTrue("State should be DISCONNECTED after backoff attempts exhaust", isDisconnected)

        sender.stopServer()
    }
}
