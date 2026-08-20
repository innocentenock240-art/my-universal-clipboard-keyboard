package com.example.sync

import com.example.data.clipboard.ClipboardCoreManager
import com.example.data.model.ClipboardItem
import com.example.data.model.ConnectionState
import com.example.data.model.Device
import com.example.sync.transport.LocalWifiTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
class Milestone592SecurePeerAuthorizationTest {

    @Test
    fun testDiscoveryDoesNotAuthorizePeer() = runBlocking {
        val receiver = LocalWifiTransport(port = 56101, customDeviceId = "dev_local_receiver")
        val unknownPeer = Device(
            deviceId = "dev_unknown_discovered_123",
            deviceName = "Unknown Discovered Phone",
            ipAddress = "192.168.1.50",
            isLocalDevice = false,
            isOnline = true,
            isPaired = false,
            connectionState = ConnectionState.DISCOVERED
        )

        receiver.addDiscoveredDevice(unknownPeer)

        // Verify "Discovery ≠ Authorization"
        assertFalse("Discovered device must NOT be authorized", receiver.isKnownPeer("dev_unknown_discovered_123"))
        val registered = receiver.discoveredDevices.value.find { it.deviceId == "dev_unknown_discovered_123" }
        assertNotNull(registered)
        assertFalse("Discovered device isPaired flag must remain false", registered!!.isPaired)
    }

    @Test
    fun testUnauthorizedPeerClipboardPayloadIsRejectedOverTcp() = runBlocking {
        val receiverPort = 56102
        val receiver = LocalWifiTransport(port = receiverPort, customDeviceId = "dev_local_receiver_56102")
        receiver.startServer()

        val content = "Secret payload from unknown attacker"
        val unauthorizedItem = ClipboardItem(
            id = "clip_unauth_001",
            sourceDeviceId = "dev_unauthorized_attacker",
            sourceDeviceName = "Attacker Phone",
            content = content,
            hash = ClipboardCoreManager.computeSha256(content)
        )

        // Connect raw TCP socket and attempt to push clipboard item
        val socket = Socket("127.0.0.1", receiverPort)
        socket.soTimeout = 3000
        val writer = PrintWriter(socket.getOutputStream(), true)
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

        writer.println(unauthorizedItem.toJsonString())
        val ackResponse = reader.readLine()
        socket.close()

        // 1. Response must indicate rejection due to unauthorized peer
        assertEquals("ERROR_UNAUTHORIZED", ackResponse)

        // 2. Incoming items stream must NOT have emitted the payload
        val receivedItem = withTimeoutOrNull(500) {
            receiver.observeIncomingItems().first()
        }
        assertNull("Unauthorized clipboard item must NOT be emitted to incoming stream", receivedItem)

        // 3. Peer must NOT be automatically added to known peers or marked paired
        assertFalse(receiver.isKnownPeer("dev_unauthorized_attacker"))
        val devEntry = receiver.discoveredDevices.value.find { it.deviceId == "dev_unauthorized_attacker" }
        if (devEntry != null) {
            assertFalse("Peer isPaired must remain false", devEntry.isPaired)
        }

        receiver.stopServer()
    }

    @Test
    fun testAuthorizedPeerClipboardPayloadIsAccepted() = runBlocking {
        val receiverPort = 56103
        val receiver = LocalWifiTransport(port = receiverPort, customDeviceId = "dev_local_receiver_56103")
        receiver.startServer()

        val trustedPeerId = "dev_trusted_peer_789"
        receiver.authorizePeer(trustedPeerId)
        assertTrue(receiver.isKnownPeer(trustedPeerId))

        val content = "Legitimate authorized sync payload"
        val authorizedItem = ClipboardItem(
            id = "clip_auth_001",
            sourceDeviceId = trustedPeerId,
            sourceDeviceName = "Trusted Laptop",
            content = content,
            hash = ClipboardCoreManager.computeSha256(content)
        )

        val incomingDeferred = async(Dispatchers.IO) {
            withTimeoutOrNull(3000) {
                receiver.observeIncomingItems().first()
            }
        }

        val socket = Socket("127.0.0.1", receiverPort)
        socket.soTimeout = 3000
        val writer = PrintWriter(socket.getOutputStream(), true)
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

        writer.println(authorizedItem.toJsonString())
        val ackResponse = reader.readLine()
        socket.close()

        assertEquals("ACK_OK", ackResponse)

        val receivedItem = incomingDeferred.await()
        assertNotNull(receivedItem)
        assertEquals("clip_auth_001", receivedItem?.id)
        assertEquals(content, receivedItem?.content)

        receiver.stopServer()
    }

    @Test
    fun testHandshakeAloneDoesNotAuthorizePeer() = runBlocking {
        val receiverPort = 56104
        val receiver = LocalWifiTransport(port = receiverPort, customDeviceId = "dev_local_receiver_56104")
        receiver.startServer()

        val unknownPeerId = "dev_unknown_handshake_456"

        // Connect socket and perform HELLO handshake
        val socket = Socket("127.0.0.1", receiverPort)
        socket.soTimeout = 3000
        val writer = PrintWriter(socket.getOutputStream(), true)
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

        writer.println("HELLO deviceId=$unknownPeerId;deviceName=Nearby_Unknown_Device")
        val ack = reader.readLine()
        assertNotNull(ack)
        assertTrue(ack.startsWith("ACK"))

        // Peer is now connected and identified, but NOT authorized
        assertFalse("Handshake must NOT automatically authorize peer", receiver.isKnownPeer(unknownPeerId))
        val registeredPeer = receiver.discoveredDevices.value.find { it.deviceId == unknownPeerId }
        assertNotNull(registeredPeer)
        assertEquals(ConnectionState.CONNECTED, registeredPeer!!.connectionState)
        assertFalse("isPaired must remain false after handshake", registeredPeer.isPaired)

        // Now attempt to send clipboard item over this connected session
        val content = "Payload attempted over unauthenticated session"
        val item = ClipboardItem(
            id = "clip_session_attempt",
            sourceDeviceId = unknownPeerId,
            sourceDeviceName = "Nearby Unknown Device",
            content = content,
            hash = ClipboardCoreManager.computeSha256(content)
        )
        writer.println(item.toJsonString())
        val itemAck = reader.readLine()
        socket.close()

        assertEquals("ERROR_UNAUTHORIZED", itemAck)

        receiver.stopServer()
    }

    @Test
    fun testExplicitAuthorizationAndRevocationLifecycle() = runBlocking {
        val receiverPort = 56105
        val receiver = LocalWifiTransport(port = receiverPort, customDeviceId = "dev_local_receiver_56105")
        receiver.startServer()

        val peerId = "dev_toggle_auth_peer"
        val content = "Dynamic authorization payload"
        val item = ClipboardItem(
            id = "clip_toggle_1",
            sourceDeviceId = peerId,
            sourceDeviceName = "Toggle Phone",
            content = content,
            hash = ClipboardCoreManager.computeSha256(content)
        )

        // --- Phase 1: Rejected while unauthorized ---
        var socket = Socket("127.0.0.1", receiverPort)
        var writer = PrintWriter(socket.getOutputStream(), true)
        var reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        writer.println(item.toJsonString())
        var ack = reader.readLine()
        socket.close()
        assertEquals("ERROR_UNAUTHORIZED", ack)

        // --- Phase 2: Explicitly Authorized -> Accepted ---
        receiver.authorizePeer(peerId)
        assertTrue(receiver.isKnownPeer(peerId))

        socket = Socket("127.0.0.1", receiverPort)
        writer = PrintWriter(socket.getOutputStream(), true)
        reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        writer.println(item.toJsonString())
        ack = reader.readLine()
        socket.close()
        assertEquals("ACK_OK", ack)

        // --- Phase 3: Revoked -> Rejected again ---
        receiver.revokePeerAuthorization(peerId)
        assertFalse(receiver.isKnownPeer(peerId))

        val item2 = ClipboardItem(
            id = "clip_toggle_2",
            sourceDeviceId = peerId,
            sourceDeviceName = "Toggle Phone",
            content = "Second payload after revocation",
            hash = ClipboardCoreManager.computeSha256("Second payload after revocation")
        )

        socket = Socket("127.0.0.1", receiverPort)
        writer = PrintWriter(socket.getOutputStream(), true)
        reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        writer.println(item2.toJsonString())
        ack = reader.readLine()
        socket.close()
        assertEquals("ERROR_UNAUTHORIZED", ack)

        receiver.stopServer()
    }
}
