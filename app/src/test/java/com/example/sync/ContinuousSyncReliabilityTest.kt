package com.example.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.adapter.TransportAdapter
import com.example.core.identity.DeviceIdentityManager
import com.example.core.identity.DeviceTrustManager
import com.example.core.policy.SyncPolicy
import com.example.core.policy.SyncPolicyManager
import com.example.core.policy.SyncRequest
import com.example.core.policy.SyncRequestType
import com.example.core.policy.SyncScope
import com.example.core.transport.LogicalPeerSession
import com.example.core.transport.PeerSessionState
import com.example.core.transport.TransportManager
import com.example.core.transport.TransportType
import com.example.data.clipboard.ClipboardCoreManager
import com.example.data.database.ClipboardDatabase
import com.example.data.database.entity.DeliveryState
import com.example.data.database.entity.PendingClipboardDeliveryEntity
import com.example.data.model.ClipboardItem
import com.example.data.model.ConnectionState
import com.example.data.model.Device
import com.example.data.repository.ClipboardRepository
import com.example.sync.transport.LocalWifiTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.PrintWriter

/**
 * Continuous Sync Reliability & Reconnection Synchronization Test Suite.
 *
 * Verifies the 25 core reliability requirements:
 * 1. Single item ACK confirmed: marks pending delivery state as ACKNOWLEDGED.
 * 2. Unacknowledged item stays PENDING and records retry attempt timestamp.
 * 3. Exponential backoff calculation: delays increase with attempt count and cap at MAX_BACKOFF_MS.
 * 4. Max retry threshold: delivery transitions to FAILED after MAX_RETRY_ATTEMPTS.
 * 5. Peer reconnection triggers immediate flush of pending deliveries for that peer.
 * 6. Reconnection delivery maintains chronological order (createdAt ASC).
 * 7. SyncPolicy PAUSED prevents automatic transmission but stores item in pending queue.
 * 8. SyncPolicy unpausing / onSyncPolicyChanged triggers delivery processing.
 * 9. SyncPolicy LOCAL_ONLY prevents transmission to remote peers.
 * 10. Blocked peer in SyncPolicy marks delivery as FAILED / skips transmission.
 * 11. Unauthorized peer rejects delivery and marks as FAILED.
 * 12. Echo loop prevention: source device ID equals local device ID is skipped.
 * 13. Deduplication: Receiver skips duplicate item hash without inserting duplicate record.
 * 14. Receiver sends ACK_DUPLICATE_SKIPPED to sender on duplicate item.
 * 15. Lost ACK followed by sender retry is safely handled idempotently by receiver.
 * 16. Multi-peer delivery tracks separate pending delivery state per target peer.
 * 17. One peer ACK success while second peer offline leaves only offline peer in PENDING state.
 * 18. Missing item in repository cleans up orphan pending delivery entity.
 * 19. Pruning acknowledged deliveries deletes old ACK records older than cutoff timestamp.
 * 20. LogicalPeerSession state transitions correctly on peer connection and disconnection.
 * 21. Corrupted payload / hash mismatch returns ERROR_HASH_MISMATCH and does not acknowledge.
 * 22. Direct TCP sendDirectSocket correctly parses ACK_OK and handles socket timeouts.
 * 23. Multiple items enqueued during network disconnect flush sequentially on reconnect.
 * 24. Rapid successive clipboard updates track distinct pending deliveries per item.
 * 25. Database schema maintains pending_deliveries table integrity and CRUD operations.
 */
@RunWith(RobolectricTestRunner::class)
class ContinuousSyncReliabilityTest {

    private lateinit var context: Context
    private lateinit var database: ClipboardDatabase
    private lateinit var repository: ClipboardRepository

    private class ControlledMockTransport(
        override val transportName: String = "ControlledMockTransport",
        override var isAvailable: Boolean = true,
        var shouldSucceed: Boolean = true
    ) : TransportAdapter {
        val sentItems = mutableListOf<Pair<ClipboardItem, String>>()

        override suspend fun startTransport() {}
        override suspend fun stopTransport() {}
        override fun observeIncomingItems(): Flow<ClipboardItem> = emptyFlow()
        override suspend fun sendItem(item: ClipboardItem, targetDeviceId: String): Boolean {
            sentItems.add(item to targetDeviceId)
            return shouldSucceed
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DeviceTrustManager.init(context)
        SyncPolicyManager.resetToDefaults()

        database = Room.inMemoryDatabaseBuilder(context, ClipboardDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ClipboardRepository(database.clipboardItemDao(), database.pendingDeliveryDao())
    }

    @After
    fun tearDown() {
        database.close()
        SyncPolicyManager.resetToDefaults()
    }

    @Test
    fun test01_SingleItemAckConfirmedMarksPendingDeliveryAcknowledged() = runBlocking {
        val mockTransport = ControlledMockTransport(shouldSucceed = true)
        val transportManager = TransportManager(listOf(mockTransport))
        val syncEngine = SyncEngine(transportManager, repository)

        DeviceTrustManager.recordPeerTrust(context, "peer_phone_1", "Phone 1")
        val item = ClipboardItem(
            id = "clip_001",
            content = "Reliable sync message 1",
            type = ClipboardItem.TYPE_TEXT,
            sourceDeviceId = "local_device",
            sourceDeviceName = "Local Device",
            hash = ClipboardCoreManager.computeSha256("Reliable sync message 1"),
            createdAt = System.currentTimeMillis()
        )
        repository.insertClipboardItem(item)

        val success = syncEngine.syncClipboardItem(item, "peer_phone_1")
        assertTrue("Sync should succeed on ACK", success)

        val delivery = repository.getDeliveryById("del_${item.id}_peer_phone_1")
        assertNotNull("Delivery entity should exist", delivery)
        assertEquals(DeliveryState.ACKNOWLEDGED.name, delivery!!.state)
        assertTrue((delivery.acknowledgedAt ?: 0L) > 0)
    }

    @Test
    fun test02_UnacknowledgedItemStaysPendingAndRecordsAttemptTimestamp() = runBlocking {
        val mockTransport = ControlledMockTransport(shouldSucceed = false)
        val transportManager = TransportManager(listOf(mockTransport))
        val syncEngine = SyncEngine(transportManager, repository)

        DeviceTrustManager.recordPeerTrust(context, "peer_phone_2", "Phone 2")
        val item = ClipboardItem(
            id = "clip_002",
            content = "Reliable sync message 2",
            type = ClipboardItem.TYPE_TEXT,
            sourceDeviceId = "local_device",
            sourceDeviceName = "Local Device",
            hash = ClipboardCoreManager.computeSha256("Reliable sync message 2"),
            createdAt = System.currentTimeMillis()
        )
        repository.insertClipboardItem(item)

        val success = syncEngine.syncClipboardItem(item, "peer_phone_2")
        assertFalse("Sync should fail when transport fails to receive ACK", success)

        val delivery = repository.getDeliveryById("del_${item.id}_peer_phone_2")
        assertNotNull(delivery)
        assertEquals(DeliveryState.PENDING.name, delivery!!.state)
        assertTrue("Last attempt should be recorded", delivery.lastAttemptAt > 0)
        assertTrue("Next attempt timestamp should be scheduled in future", delivery.nextAttemptAt > delivery.lastAttemptAt)
    }

    @Test
    fun test03_ExponentialBackoffCalculation() {
        assertEquals(2000L, SyncEngine.calculateBackoff(0))
        assertEquals(4000L, SyncEngine.calculateBackoff(1))
        assertEquals(8000L, SyncEngine.calculateBackoff(2))
        assertEquals(16000L, SyncEngine.calculateBackoff(3))
        assertEquals(32000L, SyncEngine.calculateBackoff(4))
        assertEquals(60000L, SyncEngine.calculateBackoff(5))
        assertEquals(60000L, SyncEngine.calculateBackoff(6)) // Capped at MAX_BACKOFF_MS
    }

    @Test
    fun test04_MaxRetryThresholdTransitionsToFailedState() = runBlocking {
        val mockTransport = ControlledMockTransport(shouldSucceed = false)
        val transportManager = TransportManager(listOf(mockTransport))
        val syncEngine = SyncEngine(transportManager, repository)

        DeviceTrustManager.recordPeerTrust(context, "peer_phone_4", "Phone 4")
        val item = ClipboardItem(
            id = "clip_004",
            content = "Max retry test",
            type = ClipboardItem.TYPE_TEXT,
            sourceDeviceId = "local_device",
            sourceDeviceName = "Local Device",
            hash = ClipboardCoreManager.computeSha256("Max retry test"),
            createdAt = System.currentTimeMillis()
        )
        repository.insertClipboardItem(item)

        // Enqueue item with 4 existing attempts
        val delivery = PendingClipboardDeliveryEntity(
            deliveryId = "del_clip_004_peer_phone_4",
            clipboardItemId = item.id,
            targetPeerDeviceId = "peer_phone_4",
            createdAt = item.createdAt,
            state = DeliveryState.PENDING.name,
            attemptCount = 4,
            nextAttemptAt = System.currentTimeMillis() - 100
        )
        repository.enqueuePendingDelivery(delivery)

        syncEngine.processPendingDeliveries()

        val updatedDelivery = repository.getDeliveryById("del_clip_004_peer_phone_4")
        assertNotNull(updatedDelivery)
        assertEquals(DeliveryState.FAILED.name, updatedDelivery!!.state)
        assertEquals(5, updatedDelivery.attemptCount)
    }

    @Test
    fun test05_PeerReconnectionTriggersImmediateFlush() = runBlocking {
        val mockTransport = ControlledMockTransport(shouldSucceed = true)
        val transportManager = TransportManager(listOf(mockTransport))
        val syncEngine = SyncEngine(transportManager, repository)

        DeviceTrustManager.recordPeerTrust(context, "peer_phone_5", "Phone 5")
        val item = ClipboardItem(
            id = "clip_005",
            content = "Reconnection sync test",
            type = ClipboardItem.TYPE_TEXT,
            sourceDeviceId = "local_device",
            sourceDeviceName = "Local Device",
            hash = ClipboardCoreManager.computeSha256("Reconnection sync test"),
            createdAt = System.currentTimeMillis()
        )
        repository.insertClipboardItem(item)

        val delivery = PendingClipboardDeliveryEntity(
            deliveryId = "del_clip_005_peer_phone_5",
            clipboardItemId = item.id,
            targetPeerDeviceId = "peer_phone_5",
            createdAt = item.createdAt,
            state = DeliveryState.PENDING.name,
            attemptCount = 1,
            nextAttemptAt = System.currentTimeMillis() + 60000 // In future
        )
        repository.enqueuePendingDelivery(delivery)

        // Reconnect triggers immediate flush
        syncEngine.onPeerConnected("peer_phone_5")

        // Wait brief instant for async flush job
        kotlinx.coroutines.delay(100)

        val updatedDelivery = repository.getDeliveryById("del_clip_005_peer_phone_5")
        assertNotNull(updatedDelivery)
        assertEquals(DeliveryState.ACKNOWLEDGED.name, updatedDelivery!!.state)
    }

    @Test
    fun test06_ReconnectionMaintainsChronologicalOrder() = runBlocking {
        val mockTransport = ControlledMockTransport(shouldSucceed = true)
        val transportManager = TransportManager(listOf(mockTransport))
        val syncEngine = SyncEngine(transportManager, repository)

        DeviceTrustManager.recordPeerTrust(context, "peer_phone_6", "Phone 6")
        val item1 = ClipboardItem(id = "clip_006_1", content = "First", type = ClipboardItem.TYPE_TEXT, sourceDeviceId = "local_device", createdAt = 1000L, hash = "h1")
        val item2 = ClipboardItem(id = "clip_006_2", content = "Second", type = ClipboardItem.TYPE_TEXT, sourceDeviceId = "local_device", createdAt = 2000L, hash = "h2")
        val item3 = ClipboardItem(id = "clip_006_3", content = "Third", type = ClipboardItem.TYPE_TEXT, sourceDeviceId = "local_device", createdAt = 3000L, hash = "h3")

        repository.insertClipboardItem(item1)
        repository.insertClipboardItem(item2)
        repository.insertClipboardItem(item3)

        // Insert deliveries with unordered timestamps
        repository.enqueuePendingDelivery(PendingClipboardDeliveryEntity("del_3", "clip_006_3", "peer_phone_6", createdAt = 3000L, state = DeliveryState.PENDING.name))
        repository.enqueuePendingDelivery(PendingClipboardDeliveryEntity("del_1", "clip_006_1", "peer_phone_6", createdAt = 1000L, state = DeliveryState.PENDING.name))
        repository.enqueuePendingDelivery(PendingClipboardDeliveryEntity("del_2", "clip_006_2", "peer_phone_6", createdAt = 2000L, state = DeliveryState.PENDING.name))

        syncEngine.onPeerConnected("peer_phone_6")
        kotlinx.coroutines.delay(100)

        assertEquals(3, mockTransport.sentItems.size)
        assertEquals("clip_006_1", mockTransport.sentItems[0].first.id)
        assertEquals("clip_006_2", mockTransport.sentItems[1].first.id)
        assertEquals("clip_006_3", mockTransport.sentItems[2].first.id)
    }

    @Test
    fun test07_SyncPolicyPausedPreventsTransmissionButEnqueues() = runBlocking {
        val mockTransport = ControlledMockTransport(shouldSucceed = true)
        val transportManager = TransportManager(listOf(mockTransport))
        val syncEngine = SyncEngine(transportManager, repository)

        DeviceTrustManager.recordPeerTrust(context, "peer_phone_7", "Phone 7")
        SyncPolicyManager.setSyncPaused(true)

        val item = ClipboardItem(id = "clip_007", content = "Paused sync test", type = ClipboardItem.TYPE_TEXT, sourceDeviceId = "local_device", hash = "h7")
        repository.insertClipboardItem(item)

        val success = syncEngine.syncClipboardItem(item, "peer_phone_7")
        assertFalse("Transmission should not occur when paused", success)
        assertEquals(0, mockTransport.sentItems.size)

        val delivery = repository.getDeliveryById("del_clip_007_peer_phone_7")
        assertNotNull("Delivery record must still be queued", delivery)
        assertEquals(DeliveryState.PENDING.name, delivery!!.state)
    }

    @Test
    fun test08_SyncPolicyUnpausingFlushesPendingDeliveries() = runBlocking {
        val mockTransport = ControlledMockTransport(shouldSucceed = true)
        val transportManager = TransportManager(listOf(mockTransport))
        val syncEngine = SyncEngine(transportManager, repository)

        DeviceTrustManager.recordPeerTrust(context, "peer_phone_8", "Phone 8")
        val item = ClipboardItem(id = "clip_008", content = "Unpause flush test", type = ClipboardItem.TYPE_TEXT, sourceDeviceId = "local_device", hash = "h8")
        repository.insertClipboardItem(item)

        repository.enqueuePendingDelivery(
            PendingClipboardDeliveryEntity("del_clip_008_peer_phone_8", "clip_008", "peer_phone_8", createdAt = 1000L, state = DeliveryState.PENDING.name, nextAttemptAt = 0L)
        )

        SyncPolicyManager.setSyncPaused(false)
        syncEngine.onSyncPolicyChanged()
        kotlinx.coroutines.delay(100)

        val delivery = repository.getDeliveryById("del_clip_008_peer_phone_8")
        assertNotNull(delivery)
        assertEquals(DeliveryState.ACKNOWLEDGED.name, delivery!!.state)
    }

    @Test
    fun test09_SyncPolicyLocalOnlyRejectsRemoteTransmission() = runBlocking {
        val mockTransport = ControlledMockTransport(shouldSucceed = true)
        val transportManager = TransportManager(listOf(mockTransport))
        val syncEngine = SyncEngine(transportManager, repository)

        val item = ClipboardItem(id = "clip_009", content = "Local only item", type = ClipboardItem.TYPE_TEXT, sourceDeviceId = "local_device", hash = "h9")
        repository.insertClipboardItem(item)

        val request = SyncRequest(item = item, requestedScope = SyncScope.LOCAL_ONLY)
        val success = syncEngine.executeSyncRequest(request, SyncPolicyManager.getPolicy())

        assertFalse("Local only request must not be transmitted", success)
        assertEquals(0, mockTransport.sentItems.size)
    }

    @Test
    fun test10_BlockedPeerInPolicyMarksDeliveryFailed() = runBlocking {
        val mockTransport = ControlledMockTransport(shouldSucceed = true)
        val transportManager = TransportManager(listOf(mockTransport))
        val syncEngine = SyncEngine(transportManager, repository)

        DeviceTrustManager.recordPeerTrust(context, "peer_blocked_10", "Blocked Phone")
        SyncPolicyManager.setBlockedDevices(setOf("peer_blocked_10"))

        val item = ClipboardItem(id = "clip_010", content = "Blocked peer item", type = ClipboardItem.TYPE_TEXT, sourceDeviceId = "local_device", hash = "h10")
        repository.insertClipboardItem(item)

        repository.enqueuePendingDelivery(
            PendingClipboardDeliveryEntity("del_clip_010_peer_blocked_10", "clip_010", "peer_blocked_10", state = DeliveryState.PENDING.name, nextAttemptAt = 0L)
        )

        syncEngine.processPendingDeliveries()

        val delivery = repository.getDeliveryById("del_clip_010_peer_blocked_10")
        assertNotNull(delivery)
        assertEquals(DeliveryState.FAILED.name, delivery!!.state)
        assertEquals(0, mockTransport.sentItems.size)
    }

    @Test
    fun test11_UnauthorizedPeerRejectsDelivery() = runBlocking {
        val wifiTransport = LocalWifiTransport(port = 57120, customDeviceId = "local_device")
        val transportManager = TransportManager(listOf(wifiTransport))
        val syncEngine = SyncEngine(transportManager, repository)

        // peer_unauth_11 is NOT added to DeviceTrustManager or wifiTransport known peers
        val item = ClipboardItem(id = "clip_011", content = "Unauthorized peer test", type = ClipboardItem.TYPE_TEXT, sourceDeviceId = "local_device", hash = "h11")
        repository.insertClipboardItem(item)

        repository.enqueuePendingDelivery(
            PendingClipboardDeliveryEntity("del_clip_011_peer_unauth_11", "clip_011", "peer_unauth_11", state = DeliveryState.PENDING.name, nextAttemptAt = 0L)
        )

        syncEngine.processPendingDeliveries()

        val delivery = repository.getDeliveryById("del_clip_011_peer_unauth_11")
        assertNotNull(delivery)
        assertEquals(DeliveryState.FAILED.name, delivery!!.state)
    }

    @Test
    fun test12_EchoLoopPreventionSelfDeviceSkipped() = runBlocking {
        val localId = DeviceIdentityManager.getLocalDeviceId()
        val item = ClipboardItem(id = "clip_012", content = "Echo test", type = ClipboardItem.TYPE_TEXT, sourceDeviceId = localId, hash = "h12")
        repository.insertClipboardItem(item)

        val receiverTransport = LocalWifiTransport(port = 57101, customDeviceId = localId)
        val processed = receiverTransport.processIncomingClipboardItem(item)

        assertFalse("Receiver must reject echo from self device", processed)
    }

    @Test
    fun test13_DeduplicationReceiverSkipsDuplicateItemHash() = runBlocking {
        val receiver = LocalWifiTransport(port = 57102, customDeviceId = "dev_receiver_13")
        receiver.addKnownPeer("dev_sender_13")

        val content = "Deduplication content"
        val hash = ClipboardCoreManager.computeSha256(content)
        val item1 = ClipboardItem(id = "clip_013_1", content = content, type = ClipboardItem.TYPE_TEXT, sourceDeviceId = "dev_sender_13", hash = hash)
        val item2 = ClipboardItem(id = "clip_013_2", content = content, type = ClipboardItem.TYPE_TEXT, sourceDeviceId = "dev_sender_13", hash = hash)

        val firstProcessed = receiver.processIncomingClipboardItem(item1)
        val secondProcessed = receiver.processIncomingClipboardItem(item2)

        assertTrue("First transmission must be accepted", firstProcessed)
        assertFalse("Duplicate hash transmission must be skipped", secondProcessed)
    }

    @Test
    fun test14_ReceiverSendsDuplicateSkippedAck() = runBlocking {
        val receiver = LocalWifiTransport(port = 57103, customDeviceId = "dev_receiver_14")
        receiver.addKnownPeer("dev_sender_14")

        val content = "Duplicate ACK test"
        val hash = ClipboardCoreManager.computeSha256(content)
        val item = ClipboardItem(id = "clip_014", content = content, type = ClipboardItem.TYPE_TEXT, sourceDeviceId = "dev_sender_14", hash = hash)

        val stringWriter = java.io.StringWriter()
        val printWriter = PrintWriter(stringWriter)

        receiver.processIncomingClipboardItem(item, printWriter) // First
        val firstResponse = stringWriter.toString()

        val stringWriter2 = java.io.StringWriter()
        val printWriter2 = PrintWriter(stringWriter2)
        receiver.processIncomingClipboardItem(item, printWriter2) // Duplicate
        val secondResponse = stringWriter2.toString()

        assertTrue("First response must be ACK_OK", firstResponse.contains("ACK_OK"))
        assertTrue("Second response must be ACK_DUPLICATE_SKIPPED", secondResponse.contains("ACK_DUPLICATE_SKIPPED"))
    }

    @Test
    fun test15_LostAckFollowedBySenderRetryIsHandledIdempotently() = runBlocking {
        val receiver = LocalWifiTransport(port = 57104, customDeviceId = "dev_receiver_15")
        receiver.addKnownPeer("dev_sender_15")

        val content = "Lost ACK idempotent retry"
        val hash = ClipboardCoreManager.computeSha256(content)
        val item = ClipboardItem(id = "clip_015", content = content, type = ClipboardItem.TYPE_TEXT, sourceDeviceId = "dev_sender_15", hash = hash)

        // Sender transmits first time (receiver accepts, but sender dropped connection before reading ACK)
        val res1 = receiver.processIncomingClipboardItem(item)
        assertTrue("First receive should succeed", res1)

        // Sender retries sending identical item
        val stringWriter = java.io.StringWriter()
        val res2 = receiver.processIncomingClipboardItem(item, PrintWriter(stringWriter))

        assertFalse("Second receive should be duplicate skipped", res2)
        assertTrue("Receiver responds with ACK to satisfy sender retry", stringWriter.toString().contains("ACK_DUPLICATE_SKIPPED"))
    }

    @Test
    fun test16_MultiPeerDeliveryTracksSeparatePendingStatePerPeer() = runBlocking {
        val mockTransport = ControlledMockTransport(shouldSucceed = true)
        val transportManager = TransportManager(listOf(mockTransport))
        val syncEngine = SyncEngine(transportManager, repository)

        DeviceTrustManager.recordPeerTrust(context, "peer_a_16", "Peer A")
        DeviceTrustManager.recordPeerTrust(context, "peer_b_16", "Peer B")

        val item = ClipboardItem(id = "clip_016", content = "Multi peer item", type = ClipboardItem.TYPE_TEXT, sourceDeviceId = "local_device", hash = "h16")
        repository.insertClipboardItem(item)

        syncEngine.syncClipboardItem(item, "ALL")

        val deliveryA = repository.getDeliveryById("del_clip_016_peer_a_16")
        val deliveryB = repository.getDeliveryById("del_clip_016_peer_b_16")

        assertNotNull(deliveryA)
        assertNotNull(deliveryB)
        assertEquals(DeliveryState.ACKNOWLEDGED.name, deliveryA!!.state)
        assertEquals(DeliveryState.ACKNOWLEDGED.name, deliveryB!!.state)
    }

    @Test
    fun test17_OnePeerSuccessWhileSecondPeerFailsLeavesOnlyFailedInPending() = runBlocking {
        val selectiveTransport = object : TransportAdapter {
            override val transportName: String = "SelectiveTransport"
            override val isAvailable: Boolean = true
            override suspend fun startTransport() {}
            override suspend fun stopTransport() {}
            override fun observeIncomingItems(): Flow<ClipboardItem> = emptyFlow()
            override suspend fun sendItem(item: ClipboardItem, targetDeviceId: String): Boolean {
                return targetDeviceId == "peer_online_17"
            }
        }
        val transportManager = TransportManager(listOf(selectiveTransport))
        val syncEngine = SyncEngine(transportManager, repository)

        DeviceTrustManager.recordPeerTrust(context, "peer_online_17", "Online Peer")
        DeviceTrustManager.recordPeerTrust(context, "peer_offline_17", "Offline Peer")

        val item = ClipboardItem(id = "clip_017", content = "Partial sync item", type = ClipboardItem.TYPE_TEXT, sourceDeviceId = "local_device", hash = "h17")
        repository.insertClipboardItem(item)

        syncEngine.syncClipboardItem(item, "ALL")

        val deliveryOnline = repository.getDeliveryById("del_clip_017_peer_online_17")
        val deliveryOffline = repository.getDeliveryById("del_clip_017_peer_offline_17")

        assertNotNull(deliveryOnline)
        assertNotNull(deliveryOffline)
        assertEquals(DeliveryState.ACKNOWLEDGED.name, deliveryOnline!!.state)
        assertEquals(DeliveryState.PENDING.name, deliveryOffline!!.state)
    }

    @Test
    fun test18_MissingItemCleansUpOrphanDelivery() = runBlocking {
        val mockTransport = ControlledMockTransport(shouldSucceed = true)
        val transportManager = TransportManager(listOf(mockTransport))
        val syncEngine = SyncEngine(transportManager, repository)

        DeviceTrustManager.recordPeerTrust(context, "peer_18", "Peer 18")
        repository.enqueuePendingDelivery(
            PendingClipboardDeliveryEntity("del_orphan_18", "clip_missing_18", "peer_18", state = DeliveryState.PENDING.name, nextAttemptAt = 0L)
        )

        syncEngine.processPendingDeliveries()

        val delivery = repository.getDeliveryById("del_orphan_18")
        assertNull("Orphan delivery referencing missing item should be deleted", delivery)
    }

    @Test
    fun test19_PruningAcknowledgedDeliveries() = runBlocking {
        val cutoff = System.currentTimeMillis() - 50000
        val oldAck = PendingClipboardDeliveryEntity("del_old_ack", "clip_19_1", "p1", state = DeliveryState.ACKNOWLEDGED.name, acknowledgedAt = cutoff - 10000)
        val recentAck = PendingClipboardDeliveryEntity("del_rec_ack", "clip_19_2", "p1", state = DeliveryState.ACKNOWLEDGED.name, acknowledgedAt = cutoff + 10000)
        val pending = PendingClipboardDeliveryEntity("del_pending", "clip_19_3", "p1", state = DeliveryState.PENDING.name)

        repository.enqueuePendingDelivery(oldAck)
        repository.enqueuePendingDelivery(recentAck)
        repository.enqueuePendingDelivery(pending)

        val pruned = repository.pruneAcknowledgedDeliveries(cutoff)
        assertEquals(1, pruned)

        assertNull(repository.getDeliveryById("del_old_ack"))
        assertNotNull(repository.getDeliveryById("del_rec_ack"))
        assertNotNull(repository.getDeliveryById("del_pending"))
    }

    @Test
    fun test20_LogicalPeerSessionStateTransitions() {
        val session = LogicalPeerSession(peerDeviceId = "peer_20", peerDeviceName = "Peer 20")
        assertEquals(PeerSessionState.DISCONNECTED, session.currentState)

        session.transitionTo(PeerSessionState.CONNECTING, "Starting connection")
        assertEquals(PeerSessionState.CONNECTING, session.currentState)

        session.transitionTo(PeerSessionState.CONNECTED, "Connected")
        assertEquals(PeerSessionState.CONNECTED, session.currentState)

        session.transitionTo(PeerSessionState.DISCONNECTED, "Socket closed")
        assertEquals(PeerSessionState.DISCONNECTED, session.currentState)
    }

    @Test
    fun test21_CorruptedPayloadHashMismatchRejection() = runBlocking {
        val receiver = LocalWifiTransport(port = 57105, customDeviceId = "dev_receiver_21")
        receiver.addKnownPeer("dev_sender_21")

        val stringWriter = java.io.StringWriter()
        val item = ClipboardItem(
            id = "clip_021",
            content = "Real content",
            type = ClipboardItem.TYPE_TEXT,
            sourceDeviceId = "dev_sender_21",
            hash = "tampered_hash_that_does_not_match"
        )

        val processed = receiver.processIncomingClipboardItem(item, PrintWriter(stringWriter))
        assertFalse("Hash mismatch must be rejected", processed)
        assertTrue("Receiver responds with ERROR_HASH_MISMATCH", stringWriter.toString().contains("ERROR_HASH_MISMATCH"))
    }

    @Test
    fun test22_DirectSocketAckParsing() = runBlocking {
        val port = 57106
        val receiver = LocalWifiTransport(port = port, customDeviceId = "dev_receiver_22")
        receiver.addKnownPeer("dev_sender_22")
        receiver.startServer()

        val sender = LocalWifiTransport(port = 57107, customDeviceId = "dev_sender_22")
        val content = "Direct socket ACK test"
        val hash = ClipboardCoreManager.computeSha256(content)
        val item = ClipboardItem(id = "clip_022", content = content, type = ClipboardItem.TYPE_TEXT, sourceDeviceId = "dev_sender_22", hash = hash)

        val success = sender.sendItem(item, "127.0.0.1:$port")
        assertTrue("Direct socket send with receiver ACK should return true", success)

        receiver.stopServer()
    }

    @Test
    fun test23_MultipleItemsEnqueuedDuringDisconnectFlushSequentially() = runBlocking {
        val mockTransport = ControlledMockTransport(shouldSucceed = true)
        val transportManager = TransportManager(listOf(mockTransport))
        val syncEngine = SyncEngine(transportManager, repository)

        DeviceTrustManager.recordPeerTrust(context, "peer_23", "Peer 23")

        val items = (1..5).map { idx ->
            val item = ClipboardItem(id = "clip_23_$idx", content = "Msg $idx", type = ClipboardItem.TYPE_TEXT, sourceDeviceId = "local_device", createdAt = idx * 1000L, hash = "h23_$idx")
            repository.insertClipboardItem(item)
            PendingClipboardDeliveryEntity("del_23_$idx", item.id, "peer_23", createdAt = item.createdAt, state = DeliveryState.PENDING.name)
        }

        repository.enqueuePendingDeliveries(items)

        syncEngine.onPeerConnected("peer_23")
        kotlinx.coroutines.delay(100)

        assertEquals(5, mockTransport.sentItems.size)
        for (i in 0 until 5) {
            assertEquals("clip_23_${i + 1}", mockTransport.sentItems[i].first.id)
        }
    }

    @Test
    fun test24_RapidSuccessiveClipboardUpdatesTrackDistinctDeliveries() = runBlocking {
        val mockTransport = ControlledMockTransport(shouldSucceed = true)
        val transportManager = TransportManager(listOf(mockTransport))
        val syncEngine = SyncEngine(transportManager, repository)

        DeviceTrustManager.recordPeerTrust(context, "peer_24", "Peer 24")

        for (i in 1..4) {
            val item = ClipboardItem(id = "clip_24_$i", content = "Rapid $i", type = ClipboardItem.TYPE_TEXT, sourceDeviceId = "local_device", hash = "h24_$i")
            repository.insertClipboardItem(item)
            syncEngine.syncClipboardItem(item, "peer_24")
        }

        val allCompleted = (1..4).all { idx ->
            repository.getDeliveryById("del_clip_24_${idx}_peer_24")?.state == DeliveryState.ACKNOWLEDGED.name
        }
        assertTrue("All rapid deliveries should be acknowledged", allCompleted)
    }

    @Test
    fun test25_DatabaseSchemaAndDaoOperations() = runBlocking {
        val dao = database.pendingDeliveryDao()
        val entity = PendingClipboardDeliveryEntity(
            deliveryId = "del_25_test",
            clipboardItemId = "clip_25",
            targetPeerDeviceId = "peer_25",
            state = DeliveryState.PENDING.name
        )

        dao.insertDelivery(entity)
        val retrieved = dao.getDeliveryById("del_25_test")
        assertNotNull(retrieved)
        assertEquals("clip_25", retrieved!!.clipboardItemId)

        dao.markAcknowledged("del_25_test", 123456L)
        val acked = dao.getDeliveryById("del_25_test")
        assertEquals(DeliveryState.ACKNOWLEDGED.name, acked!!.state)
        assertEquals(123456L, acked.acknowledgedAt)

        dao.deleteDelivery("del_25_test")
        assertNull(dao.getDeliveryById("del_25_test"))
    }
}
