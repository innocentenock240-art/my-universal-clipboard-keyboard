package com.example.sync

import com.example.data.clipboard.ClipboardCoreManager
import com.example.data.model.ClipboardItem
import com.example.data.model.Device
import com.example.sync.transport.LocalWifiTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
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
class EndToEndClipboardSyncTest {

    @Test
    fun testTcpSendAndReceiveClipboardItem() = runBlocking {
        val receiverPort = 55201
        val receiver = LocalWifiTransport(port = receiverPort)
        receiver.startServer()
        assertTrue(receiver.isAvailable)
        receiver.addKnownPeer("dev_sender_123")

        val sender = LocalWifiTransport(port = 55202)

        val receiverDevice = Device(
            deviceId = "dev_receiver",
            deviceName = "Phone Receiver",
            ipAddress = "127.0.0.1:$receiverPort"
        )
        sender.addDiscoveredDevice(receiverDevice)

        val itemText = "Real-time sync payload over TCP"
        val hash = ClipboardCoreManager.computeSha256(itemText)
        val testItem = ClipboardItem(
            id = "clip_sync_001",
            sourceDeviceId = "dev_sender_123",
            sourceDeviceName = "Sender Phone",
            type = "TEXT",
            content = itemText,
            hash = hash
        )

        // Start listening for incoming items before sending
        val incomingItemDeferred = async(Dispatchers.IO) {
            withTimeoutOrNull(5000) {
                receiver.observeIncomingItems().first()
            }
        }

        // Send item from sender to target IP and port
        val sentSuccess = sender.sendItem(testItem, targetDeviceId = "127.0.0.1:$receiverPort")
        assertTrue(sentSuccess)

        val receivedItem = incomingItemDeferred.await()

        assertNotNull(receivedItem)
        assertEquals("clip_sync_001", receivedItem?.id)
        assertEquals("dev_sender_123", receivedItem?.sourceDeviceId)
        assertEquals(itemText, receivedItem?.content)
        assertEquals(hash, receivedItem?.hash)

        receiver.stopServer()
        sender.stopServer()
    }

    @Test
    fun testSha256HashMismatchRejection() = runBlocking {
        val receiverPort = 55203
        val receiver = LocalWifiTransport(port = receiverPort)
        receiver.startServer()
        receiver.addKnownPeer("dev_remote")

        val tamperedItem = ClipboardItem(
            id = "clip_tampered",
            sourceDeviceId = "dev_remote",
            sourceDeviceName = "Remote Phone",
            content = "Tampered content string",
            hash = "0000000000000000000000000000000000000000000000000000000000000000" // Invalid SHA-256
        )

        var ackResponse: String? = null
        val socket = Socket("127.0.0.1", receiverPort)
        socket.soTimeout = 3000
        val writer = PrintWriter(socket.getOutputStream(), true)
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

        writer.println(tamperedItem.toJsonString())
        ackResponse = reader.readLine()
        socket.close()

        assertEquals("ERROR_HASH_MISMATCH", ackResponse)

        val receivedItem = withTimeoutOrNull(1000) {
            receiver.observeIncomingItems().first()
        }
        assertNull(receivedItem)

        receiver.stopServer()
    }

    @Test
    fun testSelfEchoLoopPrevention() = runBlocking {
        val receiverPort = 55204
        val receiver = LocalWifiTransport(port = receiverPort)
        receiver.startServer()

        val selfEchoItem = ClipboardItem(
            id = "clip_self",
            sourceDeviceId = receiver.localDeviceId, // Matches local receiver ID
            sourceDeviceName = "Self Phone",
            content = "Self echo text",
            hash = ClipboardCoreManager.computeSha256("Self echo text")
        )

        var ackResponse: String? = null
        val socket = Socket("127.0.0.1", receiverPort)
        socket.soTimeout = 3000
        val writer = PrintWriter(socket.getOutputStream(), true)
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

        writer.println(selfEchoItem.toJsonString())
        ackResponse = reader.readLine()
        socket.close()

        assertEquals("ACK_ECHO_SKIPPED", ackResponse)

        val receivedItem = withTimeoutOrNull(1000) {
            receiver.observeIncomingItems().first()
        }
        assertNull(receivedItem)

        receiver.stopServer()
    }

    @Test
    fun testDuplicatePrevention() = runBlocking {
        val receiverPort = 55205
        val receiver = LocalWifiTransport(port = receiverPort)
        receiver.startServer()
        receiver.addKnownPeer("dev_other")

        val content = "Duplicate text payload"
        val hash = ClipboardCoreManager.computeSha256(content)
        val originalItem = ClipboardItem(
            id = "clip_dup_1",
            sourceDeviceId = "dev_other",
            sourceDeviceName = "Other Phone",
            content = content,
            hash = hash
        )

        // Send first time
        val socket1 = Socket("127.0.0.1", receiverPort)
        socket1.soTimeout = 3000
        val writer1 = PrintWriter(socket1.getOutputStream(), true)
        val reader1 = BufferedReader(InputStreamReader(socket1.getInputStream()))
        writer1.println(originalItem.toJsonString())
        val ack1 = reader1.readLine()
        socket1.close()

        assertEquals("ACK_OK", ack1)

        // Send second time with same hash
        val socket2 = Socket("127.0.0.1", receiverPort)
        socket2.soTimeout = 3000
        val writer2 = PrintWriter(socket2.getOutputStream(), true)
        val reader2 = BufferedReader(InputStreamReader(socket2.getInputStream()))
        writer2.println(originalItem.toJsonString())
        val ack2 = reader2.readLine()
        socket2.close()

        assertEquals("ACK_DUPLICATE_SKIPPED", ack2)

        receiver.stopServer()
    }

    @Test
    fun testSyncEngineDispatching() = runBlocking {
        val receiverPort = 55206
        val receiver = LocalWifiTransport(port = receiverPort)
        receiver.startServer()
        receiver.addKnownPeer("dev_remote_sender")

        val senderTransport = LocalWifiTransport(port = 55207, customDeviceId = "dev_sender_55207")
        senderTransport.startServer()
        val syncEngine = SyncEngine(listOf(senderTransport))

        val content = "SyncEngine dispatch payload"
        val testItem = ClipboardItem(
            id = "clip_sync_engine_01",
            sourceDeviceId = "dev_remote_sender",
            sourceDeviceName = "Sender Phone",
            content = content,
            hash = ClipboardCoreManager.computeSha256(content)
        )

        // Start listening for incoming items on receiver before dispatch
        val incomingDeferred = async(Dispatchers.IO) {
            withTimeoutOrNull(5000) {
                receiver.observeIncomingItems().first()
            }
        }

        // Dispatch via SyncEngine directly specifying target IP and port
        val success = syncEngine.syncClipboardItem(testItem, targetDeviceId = "127.0.0.1:$receiverPort")
        assertTrue(success)

        val received = incomingDeferred.await()

        assertNotNull(received)
        assertEquals("clip_sync_engine_01", received?.id)
        assertEquals(content, received?.content)

        receiver.stopServer()
        senderTransport.stopServer()
    }
}
