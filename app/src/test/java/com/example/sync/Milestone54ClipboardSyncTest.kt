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
class Milestone54ClipboardSyncTest {

    @Test
    fun testBidirectionalSyncOverPeerSession() = runBlocking {
        val portA = 55301
        val portB = 55302

        val transportA = LocalWifiTransport(port = portA, customDeviceId = "dev_phone_a")
        val transportB = LocalWifiTransport(port = portB, customDeviceId = "dev_phone_b")

        transportA.startServer()
        transportB.startServer()
        transportB.addKnownPeer("dev_phone_a")

        val deviceB = Device(deviceId = "dev_phone_b", deviceName = "Phone B", ipAddress = "127.0.0.1:$portB")
        transportA.addDiscoveredDevice(deviceB)

        // Establish PeerSession from A -> B
        val connected = transportA.connectToDevice(deviceB)
        assertTrue("Expected A -> B connection to succeed", connected)

        // --- 1. Phone A sends item to Phone B ---
        val contentA = "Hello Phone B from Phone A"
        val itemA = ClipboardItem(
            id = "clip_a_001",
            sourceDeviceId = "dev_phone_a",
            sourceDeviceName = "Phone A",
            content = contentA,
            hash = ClipboardCoreManager.computeSha256(contentA)
        )

        val incomingOnBDeferred = async(Dispatchers.IO) {
            withTimeoutOrNull(5000) {
                transportB.observeIncomingItems().first()
            }
        }

        val sendSuccessA = transportA.sendItem(itemA, targetDeviceId = "dev_phone_b")
        assertTrue(sendSuccessA)

        val receivedOnB = incomingOnBDeferred.await()
        assertNotNull(receivedOnB)
        assertEquals("clip_a_001", receivedOnB?.id)
        assertEquals("dev_phone_a", receivedOnB?.sourceDeviceId)
        assertEquals(contentA, receivedOnB?.content)

        // --- 2. Phone B sends item back to Phone A ---
        val contentB = "Hello Phone A from Phone B"
        val itemB = ClipboardItem(
            id = "clip_b_001",
            sourceDeviceId = "dev_phone_b",
            sourceDeviceName = "Phone B",
            content = contentB,
            hash = ClipboardCoreManager.computeSha256(contentB)
        )

        val incomingOnADeferred = async(Dispatchers.IO) {
            withTimeoutOrNull(5000) {
                transportA.observeIncomingItems().first()
            }
        }

        val sendSuccessB = transportB.sendItem(itemB, targetDeviceId = "dev_phone_a")
        assertTrue(sendSuccessB)

        val receivedOnA = incomingOnADeferred.await()
        assertNotNull(receivedOnA)
        assertEquals("clip_b_001", receivedOnA?.id)
        assertEquals("dev_phone_b", receivedOnA?.sourceDeviceId)
        assertEquals(contentB, receivedOnA?.content)

        transportA.stopServer()
        transportB.stopServer()
    }

    @Test
    fun testSelfEchoAndDuplicatePrevention() = runBlocking {
        val port = 55303
        val receiver = LocalWifiTransport(port = port, customDeviceId = "dev_local_receiver")
        receiver.startServer()
        receiver.addKnownPeer("dev_remote_sender")

        val text = "Self echo or duplicate check text"
        val hash = ClipboardCoreManager.computeSha256(text)

        // 1. Self Echo
        val selfItem = ClipboardItem(
            id = "clip_self",
            sourceDeviceId = "dev_local_receiver",
            sourceDeviceName = "Self Phone",
            content = text,
            hash = hash
        )

        val socket1 = Socket("127.0.0.1", port)
        socket1.soTimeout = 3000
        val writer1 = PrintWriter(socket1.getOutputStream(), true)
        val reader1 = BufferedReader(InputStreamReader(socket1.getInputStream()))
        writer1.println(selfItem.toJsonString())
        val ack1 = reader1.readLine()
        socket1.close()

        assertEquals("ACK_ECHO_SKIPPED", ack1)

        // 2. Normal Item from Remote
        val remoteItem = ClipboardItem(
            id = "clip_remote_1",
            sourceDeviceId = "dev_remote_sender",
            sourceDeviceName = "Remote Sender",
            content = text,
            hash = hash
        )

        val socket2 = Socket("127.0.0.1", port)
        socket2.soTimeout = 3000
        val writer2 = PrintWriter(socket2.getOutputStream(), true)
        val reader2 = BufferedReader(InputStreamReader(socket2.getInputStream()))
        writer2.println(remoteItem.toJsonString())
        val ack2 = reader2.readLine()
        socket2.close()

        assertEquals("ACK_OK", ack2)

        // 3. Duplicate Item from Remote with same hash
        val socket3 = Socket("127.0.0.1", port)
        socket3.soTimeout = 3000
        val writer3 = PrintWriter(socket3.getOutputStream(), true)
        val reader3 = BufferedReader(InputStreamReader(socket3.getInputStream()))
        writer3.println(remoteItem.toJsonString())
        val ack3 = reader3.readLine()
        socket3.close()

        assertEquals("ACK_DUPLICATE_SKIPPED", ack3)

        receiver.stopServer()
    }

    @Test
    fun testMalformedJsonDoesNotCrashTransport() = runBlocking {
        val port = 55304
        val receiver = LocalWifiTransport(port = port)
        receiver.startServer()

        val socket = Socket("127.0.0.1", port)
        socket.soTimeout = 3000
        val writer = PrintWriter(socket.getOutputStream(), true)
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

        writer.println("{ \"badJson\": true, ") // Incomplete JSON
        val ack = reader.readLine()
        socket.close()

        // Malformed line processed without crashing
        val item = withTimeoutOrNull(1000) {
            receiver.observeIncomingItems().first()
        }
        assertNull(item)

        receiver.stopServer()
    }

    @Test
    fun testDisconnectDuringSendDoesNotCrash() = runBlocking {
        val port = 55305
        val receiver = LocalWifiTransport(port = port, customDeviceId = "dev_rec_55305")
        receiver.startServer()

        val sender = LocalWifiTransport(port = 55306, customDeviceId = "dev_send_55306")
        sender.startServer()

        val devRec = Device(deviceId = "dev_rec_55305", deviceName = "Receiver Phone", ipAddress = "127.0.0.1:$port")
        sender.addDiscoveredDevice(devRec)
        sender.connectToDevice(devRec)

        // Stop receiver server to simulate abrupt disconnect
        receiver.stopServer()

        val content = "Text sent during disconnect"
        val testItem = ClipboardItem(
            id = "clip_disc_1",
            sourceDeviceId = "dev_send_55306",
            sourceDeviceName = "Sender Phone",
            content = content,
            hash = ClipboardCoreManager.computeSha256(content)
        )

        // Send should fail gracefully without throwing exception
        val sendSuccess = sender.sendItem(testItem, targetDeviceId = "dev_rec_55305")
        // Will attempt to send over session or direct socket and fail gracefully
        sender.stopServer()
    }
}
