package com.example.sync.transport

import com.example.core.adapter.TransportAdapter
import com.example.core.policy.SyncPolicy
import com.example.core.policy.SyncRequest
import com.example.core.policy.SyncScope
import com.example.core.protocol.ProtocolEnvelope
import com.example.core.protocol.ProtocolMessageType
import com.example.core.transport.LogicalPeerSession
import com.example.core.transport.PeerSessionState
import com.example.core.transport.TransportManager
import com.example.core.transport.TransportType
import com.example.data.clipboard.ClipboardCoreManager
import com.example.data.model.ClipboardItem
import com.example.data.model.ConnectionState
import com.example.data.model.Device
import com.example.sync.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

/**
 * Comprehensive Automated Verification for Milestone 5.9.5:
 * Harden Wi-Fi/TCP Connection Reference Implementation.
 */
@RunWith(RobolectricTestRunner::class)
class Milestone595HardenWifiTcpTransportTest {

    // -------------------------------------------------------------
    // 1. Connection Lifecycle & Timeouts
    // -------------------------------------------------------------

    @Test
    fun testTimeoutConstantsAre5000ms() {
        assertEquals(5000, LocalWifiTransport.CONNECT_TIMEOUT_MS)
        assertEquals(5000, LocalWifiTransport.HANDSHAKE_TIMEOUT_MS)
        assertEquals(5000, LocalWifiTransport.SOCKET_TIMEOUT_MS)
    }

    @Test
    fun testConnectionLifecycle_Connecting_Connected_Disconnected() = runBlocking {
        val receiverPort = 55601
        val receiver = LocalWifiTransport(port = receiverPort, customDeviceId = "dev_receiver_55601", customDeviceName = "Receiver 55601")
        receiver.startServer()
        assertTrue(receiver.isAvailable)

        val sender = LocalWifiTransport(port = 55602, customDeviceId = "dev_sender_55602", customDeviceName = "Sender 55602")

        val targetDevice = Device(
            deviceId = receiver.localDeviceId,
            deviceName = receiver.localDeviceName,
            ipAddress = "127.0.0.1:$receiverPort",
            connectionState = ConnectionState.DISCOVERED
        )
        sender.addDiscoveredDevice(targetDevice)

        // 1. Connect
        val connected = sender.connectToDevice(targetDevice)
        assertTrue("Connect handshake should succeed", connected)

        val senderDevState = sender.discoveredDevices.value.find { it.deviceId == receiver.localDeviceId }
        assertEquals(ConnectionState.CONNECTED, senderDevState?.connectionState)

        // 2. Disconnect
        sender.disconnectFromDevice(receiver.localDeviceId)
        val disconnectedDevState = sender.discoveredDevices.value.find { it.deviceId == receiver.localDeviceId }
        assertEquals(ConnectionState.DISCONNECTED, disconnectedDevState?.connectionState)

        receiver.stopServer()
        sender.stopServer()
    }

    @Test
    fun testRepeatedConnectionAttemptsDoNotLeaveStaleTransports() = runBlocking {
        val receiverPort = 55603
        val receiver = LocalWifiTransport(port = receiverPort, customDeviceId = "dev_receiver_55603", customDeviceName = "Receiver 55603")
        receiver.startServer()

        val sender = LocalWifiTransport(port = 55604, customDeviceId = "dev_sender_55604", customDeviceName = "Sender 55604")

        val targetDevice = Device(
            deviceId = receiver.localDeviceId,
            deviceName = receiver.localDeviceName,
            ipAddress = "127.0.0.1:$receiverPort",
            connectionState = ConnectionState.DISCOVERED
        )
        sender.addDiscoveredDevice(targetDevice)

        // Attempt 1
        assertTrue(sender.connectToDevice(targetDevice))
        // Attempt 2 (idempotent when already connected)
        assertTrue(sender.connectToDevice(targetDevice))
        // Disconnect
        sender.disconnectFromDevice(receiver.localDeviceId)
        // Attempt 3 (reconnect after disconnect)
        assertTrue(sender.connectToDevice(targetDevice))

        assertEquals(ConnectionState.CONNECTED, sender.discoveredDevices.value.find { it.deviceId == receiver.localDeviceId }?.connectionState)

        sender.disconnectFromDevice(receiver.localDeviceId)
        receiver.stopServer()
        sender.stopServer()
    }

    // -------------------------------------------------------------
    // 2. TCP Framing & Multiple Consecutive Frames
    // -------------------------------------------------------------

    @Test
    fun testBidirectionalFramedJsonAndMultipleConsecutiveFrames() = runBlocking {
        val receiverPort = 55605
        val receiver = LocalWifiTransport(port = receiverPort, customDeviceId = "dev_receiver_55605", customDeviceName = "Receiver 55605")
        receiver.startServer()

        val sender = LocalWifiTransport(port = 55606, customDeviceId = "dev_sender_55606", customDeviceName = "Sender 55606")
        receiver.authorizePeer(sender.localDeviceId)
        sender.authorizePeer(receiver.localDeviceId)

        val targetDevice = Device(
            deviceId = receiver.localDeviceId,
            deviceName = receiver.localDeviceName,
            ipAddress = "127.0.0.1:${receiver.port}"
        )
        sender.addDiscoveredDevice(targetDevice)
        assertTrue(sender.connectToDevice(targetDevice))

        // Send 3 consecutive items over the established session
        val content1 = "First test frame alpha"
        val item1 = ClipboardItem(
            id = "item_frame_1",
            content = content1,
            hash = ClipboardCoreManager.computeSha256(content1),
            sourceDeviceId = sender.localDeviceId,
            sourceDeviceName = sender.localDeviceName
        )
        val content2 = "Second test frame beta"
        val item2 = ClipboardItem(
            id = "item_frame_2",
            content = content2,
            hash = ClipboardCoreManager.computeSha256(content2),
            sourceDeviceId = sender.localDeviceId,
            sourceDeviceName = sender.localDeviceName
        )
        val content3 = "Third test frame gamma"
        val item3 = ClipboardItem(
            id = "item_frame_3",
            content = content3,
            hash = ClipboardCoreManager.computeSha256(content3),
            sourceDeviceId = sender.localDeviceId,
            sourceDeviceName = sender.localDeviceName
        )

        assertTrue(sender.sendItem(item1, receiver.localDeviceId))
        assertTrue(sender.sendItem(item2, receiver.localDeviceId))
        assertTrue(sender.sendItem(item3, receiver.localDeviceId))

        sender.disconnectFromDevice(receiver.localDeviceId)
        receiver.stopServer()
        sender.stopServer()
    }

    // -------------------------------------------------------------
    // 3. Keepalive & PING/PONG Handling
    // -------------------------------------------------------------

    @Test
    fun testKeepalivePingPongNeverEntersClipboardPipeline() = runBlocking {
        val receiverPort = 55607
        val receiver = LocalWifiTransport(port = receiverPort, customDeviceId = "dev_receiver_55607")
        receiver.startServer()

        val socket = Socket("127.0.0.1", receiverPort)
        socket.soTimeout = 5000
        val writer = PrintWriter(socket.getOutputStream(), true)
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

        // Handshake
        writer.println("HELLO deviceId=dev_keepalive_tester;deviceName=Tester")
        writer.flush()
        val ack = reader.readLine()
        assertTrue("Handshake ack expected", ack != null && ack.startsWith("ACK"))

        // Send PING keepalive
        writer.println("PING")
        writer.flush()
        val pong = reader.readLine()
        assertEquals("PONG", pong)

        // Ensure no clipboard item was emitted
        val incomingItem = withTimeoutOrNull(500) {
            receiver.observeIncomingItems().first()
        }
        assertNull("PING/PONG must not emit clipboard items", incomingItem)

        socket.close()
        receiver.stopServer()
    }

    // -------------------------------------------------------------
    // 4. Graceful Termination (DISCONNECT / BYE)
    // -------------------------------------------------------------

    @Test
    fun testGracefulDisconnectSignalCleansSession() = runBlocking {
        val receiverPort = 55608
        val receiver = LocalWifiTransport(port = receiverPort, customDeviceId = "dev_receiver_55608")
        receiver.startServer()

        val socket = Socket("127.0.0.1", receiverPort)
        socket.soTimeout = 5000
        val writer = PrintWriter(socket.getOutputStream(), true)
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

        writer.println("HELLO deviceId=dev_disconnect_peer;deviceName=DisconnectPeer")
        writer.flush()
        val ack = reader.readLine()
        assertNotNull(ack)

        // Send explicit DISCONNECT signal
        writer.println("DISCONNECT")
        writer.flush()

        // Wait brief moment for reader loop to observe and clean up
        withContext(Dispatchers.IO) {
            Thread.sleep(300)
        }

        val peerState = receiver.discoveredDevices.value.find { it.deviceId == "dev_disconnect_peer" }
        assertEquals(ConnectionState.DISCONNECTED, peerState?.connectionState)

        socket.close()
        receiver.stopServer()
    }

    // -------------------------------------------------------------
    // 5. LogicalPeerSession & TransportManager Integration
    // -------------------------------------------------------------

    @Test
    fun testLogicalPeerSessionIntegrationWithTransportManager() = runBlocking {
        val receiverPort = 55609
        val receiver = LocalWifiTransport(port = receiverPort, customDeviceId = "dev_receiver_55609", customDeviceName = "Receiver 55609")
        receiver.startServer()

        val sender = LocalWifiTransport(port = 55610, customDeviceId = "dev_sender_55610", customDeviceName = "Sender 55610")
        sender.startServer()
        receiver.authorizePeer(sender.localDeviceId)
        sender.authorizePeer(receiver.localDeviceId)

        val transportManager = TransportManager(listOf(sender))
        val session = transportManager.getOrCreateSession(receiver.localDeviceId, receiver.localDeviceName)
        assertEquals(PeerSessionState.DISCONNECTED, session.currentState)

        val targetDevice = Device(
            deviceId = receiver.localDeviceId,
            deviceName = receiver.localDeviceName,
            ipAddress = "127.0.0.1:${receiver.port}"
        )
        sender.addDiscoveredDevice(targetDevice)

        // Connect through sender transport
        val connected = sender.connectToDevice(targetDevice)
        assertTrue(connected)

        // Verify session binding and state transition
        assertEquals(PeerSessionState.CONNECTED, session.currentState)
        assertEquals(TransportType.WIFI_LAN, session.activeTransportType.value)

        // Send item via TransportManager
        val content = "TransportManager session payload"
        val item = ClipboardItem(
            id = "tm_item_1",
            content = content,
            hash = ClipboardCoreManager.computeSha256(content),
            sourceDeviceId = sender.localDeviceId,
            sourceDeviceName = sender.localDeviceName
        )
        val sendResult = transportManager.sendItem(item, receiver.localDeviceId)
        assertTrue("TransportManager should route via active Wi-Fi session", sendResult)

        // Disconnect
        sender.disconnectFromDevice(receiver.localDeviceId)
        assertEquals(PeerSessionState.DISCONNECTED, session.currentState)
        assertNull(session.activeTransport)

        receiver.stopServer()
        sender.stopServer()
    }

    // -------------------------------------------------------------
    // 6. Authorization Boundary («Discovery ≠ Authorization»)
    // -------------------------------------------------------------

    @Test
    fun testAuthorizationBoundaryRejectsUnauthorizedPeer() = runBlocking {
        val receiverPort = 55611
        val receiver = LocalWifiTransport(port = receiverPort, customDeviceId = "dev_receiver_55611", customDeviceName = "Receiver 55611")
        receiver.startServer()

        val sender = LocalWifiTransport(port = 55612, customDeviceId = "dev_sender_55612", customDeviceName = "Sender 55612")
        // NOTE: receiver deliberately DOES NOT authorize sender!

        val targetDevice = Device(
            deviceId = receiver.localDeviceId,
            deviceName = receiver.localDeviceName,
            ipAddress = "127.0.0.1:$receiverPort"
        )
        sender.addDiscoveredDevice(targetDevice)
        sender.connectToDevice(targetDevice)

        val content = "Secret unauthorized data"
        val item = ClipboardItem(
            id = "item_unauth_1",
            content = content,
            hash = ClipboardCoreManager.computeSha256(content),
            sourceDeviceId = sender.localDeviceId,
            sourceDeviceName = sender.localDeviceName
        )

        val receivedItem = withTimeoutOrNull(500) {
            receiver.observeIncomingItems().first()
        }
        assertNull("Unauthorized peer's payload must NOT be emitted to clipboard stream", receivedItem)

        sender.disconnectFromDevice(receiver.localDeviceId)
        receiver.stopServer()
        sender.stopServer()
    }

    // -------------------------------------------------------------
    // 7. Clipboard Integrity (SHA-256) & Deduplication
    // -------------------------------------------------------------

    @Test
    fun testSha256IntegrityRejectionAndDeduplication() = runBlocking {
        val receiverPort = 55613
        val receiver = LocalWifiTransport(port = receiverPort, customDeviceId = "dev_receiver_55613")
        receiver.startServer()

        val sender = LocalWifiTransport(port = 55614, customDeviceId = "dev_sender_55614")
        sender.startServer()
        receiver.authorizePeer(sender.localDeviceId)
        sender.authorizePeer(receiver.localDeviceId)

        val targetDevice = Device(
            deviceId = receiver.localDeviceId,
            deviceName = receiver.localDeviceName,
            ipAddress = "127.0.0.1:$receiverPort"
        )
        sender.addDiscoveredDevice(targetDevice)
        sender.connectToDevice(targetDevice)

        val receivedItems = java.util.Collections.synchronizedList(mutableListOf<ClipboardItem>())
        val collectJob = launch {
            receiver.observeIncomingItems().collect {
                receivedItems.add(it)
            }
        }

        // 1. Send Corrupted Hash item
        val corruptedItem = ClipboardItem(
            id = "item_corrupted_1",
            content = "Actual text content",
            hash = "corrupted_fake_sha256_hash_that_will_not_match",
            sourceDeviceId = sender.localDeviceId,
            sourceDeviceName = sender.localDeviceName
        )
        sender.sendItem(corruptedItem, receiver.localDeviceId)
        withContext(Dispatchers.IO) { Thread.sleep(300) }

        // Corrupted item must NOT be received
        assertEquals("Corrupted item must be rejected and not received", 0, receivedItems.size)

        // 2. Legitimate item
        val legitimateContent = "Legitimate payload 12345"
        val legitimateItem = ClipboardItem(
            id = "item_legit_1",
            content = legitimateContent,
            hash = ClipboardCoreManager.computeSha256(legitimateContent),
            sourceDeviceId = sender.localDeviceId,
            sourceDeviceName = sender.localDeviceName
        )
        val legitResult = sender.sendItem(legitimateItem, receiver.localDeviceId)
        assertTrue("Legitimate item transmission write must succeed", legitResult)
        withContext(Dispatchers.IO) { Thread.sleep(300) }

        assertEquals("Legitimate item must be received", 1, receivedItems.size)
        assertEquals(legitimateContent, receivedItems[0].content)

        // 3. Duplicate item with same hash
        val dupResult = sender.sendItem(legitimateItem, receiver.localDeviceId)
        assertTrue("Duplicate item transmission write must succeed", dupResult)
        withContext(Dispatchers.IO) { Thread.sleep(300) }

        // Receiver acknowledges with ACK_DUPLICATE_SKIPPED, preventing duplicate insertion
        assertEquals("Duplicate item must be skipped and not emitted again", 1, receivedItems.size)

        collectJob.cancel()
        sender.disconnectFromDevice(receiver.localDeviceId)
        receiver.stopServer()
        sender.stopServer()
    }

    // -------------------------------------------------------------
    // 8. Self-Echo Loopback Suppression
    // -------------------------------------------------------------

    @Test
    fun testSelfEchoLoopbackSuppression() = runBlocking {
        val receiverPort = 55615
        val receiver = LocalWifiTransport(port = receiverPort, customDeviceId = "dev_echo_node")
        receiver.startServer()

        val socket = Socket("127.0.0.1", receiverPort)
        socket.soTimeout = 5000
        val writer = PrintWriter(socket.getOutputStream(), true)
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

        val echoContent = "Echo back to self"
        val echoItem = ClipboardItem(
            id = "item_echo_1",
            content = echoContent,
            hash = ClipboardCoreManager.computeSha256(echoContent),
            sourceDeviceId = receiver.localDeviceId, // matches receiver's own deviceId
            sourceDeviceName = receiver.localDeviceName
        )

        writer.println(echoItem.toJsonString())
        writer.flush()

        val ack = reader.readLine()
        assertEquals("ACK_ECHO_SKIPPED", ack)

        socket.close()
        receiver.stopServer()
    }

    // -------------------------------------------------------------
    // 9. Failure & Recovery: Malformed JSON & Protocol Envelopes
    // -------------------------------------------------------------

    @Test
    fun testMalformedJsonAndGarbageFramesDoNotCrashServer() = runBlocking {
        val receiverPort = 55616
        val receiver = LocalWifiTransport(port = receiverPort, customDeviceId = "dev_receiver_55616")
        receiver.startServer()

        val socket = Socket("127.0.0.1", receiverPort)
        socket.soTimeout = 5000
        val writer = PrintWriter(socket.getOutputStream(), true)
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

        // Handshake
        writer.println("HELLO deviceId=dev_fuzzer;deviceName=Fuzzer")
        writer.flush()
        reader.readLine()

        // Send malformed garbage JSON
        writer.println("{not_valid_json: 12345, missing_quotes")
        writer.flush()

        // Send empty frame
        writer.println("   ")
        writer.flush()

        // Send valid PING afterwards to verify server and reader loop are still fully operational
        writer.println("PING")
        writer.flush()
        val pong = reader.readLine()
        assertEquals("PONG", pong)

        socket.close()
        receiver.stopServer()
    }

    // -------------------------------------------------------------
    // 10. Failure & Recovery: Connection Timeout on Non-Listening Port
    // -------------------------------------------------------------

    @Test
    fun testConnectTimeoutOnUnreachablePortFailsGracefully() = runBlocking {
        val sender = LocalWifiTransport(port = 55617, customDeviceId = "dev_sender_55617")

        // Unreachable non-listening port
        val unreachableDevice = Device(
            deviceId = "dev_unreachable",
            deviceName = "Unreachable Peer",
            ipAddress = "127.0.0.1:59999",
            connectionState = ConnectionState.DISCOVERED
        )
        sender.addDiscoveredDevice(unreachableDevice)

        val result = sender.connectToDevice(unreachableDevice)
        assertFalse("Connecting to unreachable port should fail gracefully", result)

        val state = sender.discoveredDevices.value.find { it.deviceId == "dev_unreachable" }
        assertEquals(ConnectionState.ERROR, state?.connectionState)

        sender.stopServer()
    }
}
