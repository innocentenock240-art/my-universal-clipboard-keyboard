package com.example.sync

import com.example.core.adapter.TransportAdapter
import com.example.core.policy.SyncPolicy
import com.example.core.policy.SyncRequest
import com.example.core.policy.SyncRequestType
import com.example.core.policy.SyncScope
import com.example.core.transport.LogicalPeerSession
import com.example.core.transport.PeerSessionState
import com.example.core.transport.TransportManager
import com.example.core.transport.TransportType
import com.example.data.clipboard.ClipboardCoreManager
import com.example.data.model.ClipboardItem
import com.example.data.model.Device
import com.example.sync.transport.BluetoothTransportAdapter
import com.example.sync.transport.LocalWifiTransport
import com.example.sync.transport.WifiDirectTransportAdapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Milestone 5.9.4 — Transport Session Management Test Suite
 *
 * Verifies:
 * 1. A logical session is keyed by peer device ID.
 * 2. Repeated operations for the same peer reuse the logical session.
 * 3. Different peers receive distinct logical sessions.
 * 4. Wi-Fi/TCP establishes a session for an authorized peer and preserves peer identity.
 * 5. Unauthorized peers cannot establish a usable synchronization session (Connected != Authorized).
 * 6. Session state changes correctly across lifecycle (DISCONNECTED, CONNECTED, SWITCHING, FAILED).
 * 7. Transport failure does not change the peer identity.
 * 8. Multi-transport priority and failover selection selects eligible transports.
 * 9. Unsupported placeholder adapters (Bluetooth / Wi-Fi Direct) do not report fake successful transmission.
 * 10. SyncEngine operates through peer sessions rather than bypassing them.
 * 11. Existing authorization checks and sync policies remain strictly enforced.
 */
@RunWith(RobolectricTestRunner::class)
class Milestone594TransportSessionManagementTest {

    private class MockFailingTransport(
        override val transportName: String = "Failing Transport",
        override val isAvailable: Boolean = true
    ) : TransportAdapter {
        var sendAttemptCount = 0
        override suspend fun startTransport() {}
        override suspend fun stopTransport() {}
        override suspend fun sendItem(item: ClipboardItem, targetDeviceId: String): Boolean {
            sendAttemptCount++
            return false
        }
        override fun observeIncomingItems(): Flow<ClipboardItem> = emptyFlow()
    }

    private class MockWorkingSecondaryTransport(
        override val transportName: String = "Secondary Wired Transport",
        override val isAvailable: Boolean = true
    ) : TransportAdapter {
        var sendAttemptCount = 0
        var lastSentItem: ClipboardItem? = null
        var lastTargetDeviceId: String? = null
        override suspend fun startTransport() {}
        override suspend fun stopTransport() {}
        override suspend fun sendItem(item: ClipboardItem, targetDeviceId: String): Boolean {
            sendAttemptCount++
            lastSentItem = item
            lastTargetDeviceId = targetDeviceId
            return true
        }
        override fun observeIncomingItems(): Flow<ClipboardItem> = emptyFlow()
    }

    // 1. A logical session is keyed by peer device ID
    @Test
    fun testLogicalSessionKeyedByPeerDeviceId() {
        val manager = TransportManager(emptyList())
        val peerId = "uclip_dev_peer_alpha_123"
        val session = manager.getOrCreateSession(peerId, "Alpha Phone")

        assertEquals(peerId, session.peerDeviceId)
        assertEquals("Alpha Phone", session.peerDeviceName)
        assertEquals(PeerSessionState.DISCONNECTED, session.currentState)
    }

    // 2. Repeated operations for the same peer reuse the logical session
    @Test
    fun testRepeatedOperationsReuseSameLogicalSession() {
        val manager = TransportManager(emptyList())
        val peerId = "uclip_dev_peer_beta_456"

        val session1 = manager.getOrCreateSession(peerId, "Beta Device")
        val session2 = manager.getOrCreateSession(peerId)
        val session3 = manager.getSession(peerId)

        assertSame("Subsequent lookups must return the exact same session instance", session1, session2)
        assertSame("getSession must return the existing session instance", session1, session3)
        assertEquals(1, manager.getAllSessions().size)
    }

    // 3. Different peers receive different sessions
    @Test
    fun testDifferentPeersReceiveDifferentSessions() {
        val manager = TransportManager(emptyList())
        val sessionA = manager.getOrCreateSession("uclip_dev_peer_a", "Device A")
        val sessionB = manager.getOrCreateSession("uclip_dev_peer_b", "Device B")

        assertNotEquals(sessionA.peerDeviceId, sessionB.peerDeviceId)
        assertFalse(sessionA === sessionB)
        assertEquals(2, manager.getAllSessions().size)
    }

    // 4. Wi-Fi/TCP can establish a session for an authorized peer
    @Test
    fun testWifiTcpEstablishesSessionForAuthorizedPeer() = runBlocking {
        val receiverPort = 55601
        val receiver = LocalWifiTransport(port = receiverPort, customDeviceId = "dev_receiver_55601")
        receiver.startServer()
        receiver.authorizePeer("dev_sender_55602")

        val sender = LocalWifiTransport(port = 55602, customDeviceId = "dev_sender_55602")
        sender.startServer()
        val targetDevice = Device(
            deviceId = receiver.localDeviceId,
            deviceName = receiver.localDeviceName,
            ipAddress = "127.0.0.1:$receiverPort"
        )
        sender.addDiscoveredDevice(targetDevice)
        sender.authorizePeer(receiver.localDeviceId)

        val manager = TransportManager(listOf(sender))
        val session = manager.getOrCreateSession(receiver.localDeviceId, receiver.localDeviceName)

        val item = ClipboardItem(
            id = "clip_session_001",
            sourceDeviceId = sender.localDeviceId,
            sourceDeviceName = "Sender Device",
            content = "Session-driven clipboard item",
            hash = ClipboardCoreManager.computeSha256("Session-driven clipboard item")
        )

        val success = manager.sendToPeerSession(session, item)
        assertTrue("Send over logical session to authorized peer must succeed", success)
        assertEquals(PeerSessionState.CONNECTED, session.currentState)
        assertEquals(TransportType.WIFI_LAN, session.activeTransportType.value)
        assertEquals(sender, session.activeTransport)
        assertTrue("Session lastSuccessfulCommunication must be updated", session.lastSuccessfulCommunication > 0L)

        receiver.stopServer()
        sender.stopServer()
    }

    // 5. Unauthorized peers cannot establish a usable synchronization session
    @Test
    fun testUnauthorizedPeerRejectedFromSessionSync() = runBlocking {
        val receiverPort = 55603
        val receiver = LocalWifiTransport(port = receiverPort, customDeviceId = "dev_receiver_55603")
        receiver.startServer()

        val sender = LocalWifiTransport(port = 55604, customDeviceId = "dev_sender_55604")
        val unauthorizedPeerId = "unauthorized_peer_999"

        val manager = TransportManager(listOf(sender))
        val session = manager.getOrCreateSession(unauthorizedPeerId, "Rogue Device")

        val item = ClipboardItem(
            id = "clip_unauth_001",
            sourceDeviceId = sender.localDeviceId,
            sourceDeviceName = "Sender Device",
            content = "Secret Payload",
            hash = ClipboardCoreManager.computeSha256("Secret Payload")
        )

        val success = manager.sendToPeerSession(session, item)
        assertFalse("Transmission to unauthorized peer session must fail", success)
        assertEquals("Session state must transition to FAILED for unauthorized peer", PeerSessionState.FAILED, session.currentState)
        assertTrue("Failure reason must mention authorization", session.lastFailureReason?.contains("authorized", ignoreCase = true) == true)

        receiver.stopServer()
        sender.stopServer()
    }

    // 6. Session state changes correctly during connection lifecycle
    @Test
    fun testSessionLifecycleStateTransitions() {
        val session = LogicalPeerSession(peerDeviceId = "uclip_dev_peer_lifecycle")
        assertEquals(PeerSessionState.DISCONNECTED, session.currentState)

        session.transitionTo(PeerSessionState.CONNECTING)
        assertEquals(PeerSessionState.CONNECTING, session.currentState)

        val dummyTransport = MockWorkingSecondaryTransport()
        session.bindTransport(dummyTransport, TransportType.USB_WIRED)
        assertEquals(PeerSessionState.CONNECTED, session.currentState)
        assertEquals(TransportType.USB_WIRED, session.activeTransportType.value)
        assertEquals(dummyTransport, session.activeTransport)

        session.recordFailure("Link down")
        assertEquals(PeerSessionState.DEGRADED, session.currentState)
        assertEquals("Link down", session.lastFailureReason)

        session.recordFailure("Link down 2")
        session.recordFailure("Link down 3")
        assertEquals(PeerSessionState.FAILED, session.currentState)

        session.unbindTransport()
        assertEquals(PeerSessionState.DISCONNECTED, session.currentState)
        assertNull(session.activeTransport)
        assertNull(session.activeTransportType.value)
    }

    // 7. Transport failure does not change the peer identity
    @Test
    fun testTransportFailurePreservesPeerIdentity() = runBlocking {
        val failingTransport = MockFailingTransport()
        val manager = TransportManager(listOf(failingTransport))
        val persistentPeerId = "uclip_dev_peer_stable_identity_777"
        val session = manager.getOrCreateSession(persistentPeerId, "Stable Peer")

        val item = ClipboardItem(
            id = "clip_fail_001",
            sourceDeviceId = "dev_local",
            content = "Attempt content",
            hash = ClipboardCoreManager.computeSha256("Attempt content")
        )

        val success = manager.sendToPeerSession(session, item)
        assertFalse("Send with failing transport must return false", success)

        // Peer identity must remain strictly intact
        assertEquals(persistentPeerId, session.peerDeviceId)
        assertEquals("Stable Peer", session.peerDeviceName)
        assertEquals(PeerSessionState.FAILED, session.currentState)
        assertSame(session, manager.getSession(persistentPeerId))
    }

    // 8. Failover selection chooses another eligible transport when primary fails
    @Test
    fun testFailoverSelectsSecondaryTransportWhilePreservingSession() = runBlocking {
        val primaryFailing = MockFailingTransport("Primary Wi-Fi (Down)")
        val secondaryWorking = MockWorkingSecondaryTransport("Secondary Ethernet (Up)")

        val manager = TransportManager(listOf(primaryFailing, secondaryWorking))
        val peerId = "uclip_dev_peer_failover_test"
        val session = manager.getOrCreateSession(peerId, "Failover Peer")

        val item = ClipboardItem(
            id = "clip_failover_001",
            sourceDeviceId = "dev_local",
            content = "Failover content payload",
            hash = ClipboardCoreManager.computeSha256("Failover content payload")
        )

        val success = manager.sendToPeerSession(session, item)
        assertTrue("Failover to secondary transport must succeed", success)
        assertEquals(1, primaryFailing.sendAttemptCount)
        assertEquals(1, secondaryWorking.sendAttemptCount)
        assertEquals(PeerSessionState.CONNECTED, session.currentState)
        assertEquals(secondaryWorking, session.activeTransport)
        assertEquals(peerId, session.peerDeviceId)
    }

    // 9. Unsupported placeholder adapters cannot report fake successful transmission
    @Test
    fun testPlaceholderAdaptersDoNotReportFakeSuccess() = runBlocking {
        val bluetooth = BluetoothTransportAdapter()
        val wifiDirect = WifiDirectTransportAdapter()

        assertFalse("Bluetooth placeholder must report isAvailable = false", bluetooth.isAvailable)
        assertFalse("Wi-Fi Direct placeholder must report isAvailable = false", wifiDirect.isAvailable)

        val item = ClipboardItem(id = "clip_mock", sourceDeviceId = "dev_1", content = "test", hash = "h")
        assertFalse("Bluetooth sendItem must return false", bluetooth.sendItem(item, "target_1"))
        assertFalse("Wi-Fi Direct sendItem must return false", wifiDirect.sendItem(item, "target_2"))
    }

    // 10. Synchronization engine uses peer session rather than bypassing it
    @Test
    fun testSyncEngineUsesPeerSessionArchitecture() = runBlocking {
        val workingTransport = MockWorkingSecondaryTransport()
        val manager = TransportManager(listOf(workingTransport))
        val engine = SyncEngine(manager)

        val peerId = "uclip_dev_peer_sync_engine_cuj"
        val item = ClipboardItem(
            id = "clip_engine_001",
            sourceDeviceId = "dev_local",
            content = "SyncEngine Session Integration",
            hash = ClipboardCoreManager.computeSha256("SyncEngine Session Integration")
        )

        val request = SyncRequest(
            item = item,
            requestType = SyncRequestType.TARGETED,
            targetDeviceId = peerId,
            requestedScope = SyncScope.SYNC_TARGET
        )

        val result = engine.executeSyncRequest(request, SyncPolicy())
        assertTrue("SyncEngine targeted dispatch must succeed", result)

        val session = engine.getPeerSession(peerId)
        assertNotNull("SyncEngine must have created/updated the logical peer session", session)
        assertEquals(peerId, session?.peerDeviceId)
        assertEquals(PeerSessionState.CONNECTED, session?.currentState)
        assertEquals(workingTransport, session?.activeTransport)
    }
}
