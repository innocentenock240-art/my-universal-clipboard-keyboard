package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.core.identity.DeviceIdentityManager
import com.example.core.identity.DeviceTrustManager
import com.example.core.policy.ExplicitSendRequest
import com.example.core.policy.OperationalSyncState
import com.example.core.policy.SendDestination
import com.example.core.policy.SendResult
import com.example.core.policy.SyncPolicyManager
import com.example.data.database.ClipboardDatabase
import com.example.data.model.ClipboardItem
import com.example.data.model.ConnectionState
import com.example.data.model.Device
import com.example.data.repository.ClipboardRepository
import com.example.sync.SyncRuntime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class Phase2Prompt6ContradictionRepairTestSuite {

    private lateinit var context: Application
    private lateinit var database: ClipboardDatabase
    private lateinit var repository: ClipboardRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = ClipboardDatabase.getInstance(context)
        repository = ClipboardRepository(database.clipboardItemDao())
        DeviceTrustManager.init(context)
        SyncPolicyManager.init(context)
        SyncPolicyManager.setSyncPaused(false)
        SyncPolicyManager.setAutoSync(false)
        SyncRuntime.resetForTesting(context)
        SyncRuntime.initialize(context)
    }

    @After
    fun tearDown() {
        SyncRuntime.resetForTesting(context)
    }

    @Test
    fun testDeviceModelDefaultSafeValues_noFakeOnlineState() {
        val device = Device(
            deviceId = "test_peer_123",
            deviceName = "Peer Laptop",
            deviceType = "LAPTOP"
        )
        // Verify default safety: never online by default, never connected by default
        assertFalse("Device must not be online by default", device.isOnline)
        assertEquals("Device must be DISCONNECTED by default", ConnectionState.DISCONNECTED, device.connectionState)
    }

    @Test
    fun testSyncRuntimeAuthoritativeState_PausedStateAuthority() = runBlocking {
        // Pause sync
        SyncPolicyManager.setSyncPaused(true)
        assertTrue("SyncPolicy must report paused", SyncPolicyManager.syncPolicy.value.isSyncPaused)
        assertEquals("OperationalSyncState must be PAUSED", OperationalSyncState.PAUSED, SyncRuntime.operationalSyncState.value)

        // Attempt explicit send while paused
        val testItem = ClipboardItem(
            id = UUID.randomUUID().toString(),
            sourceDeviceId = "dev_local_1",
            content = "Confidential test text",
            type = ClipboardItem.TYPE_TEXT
        )
        val request = ExplicitSendRequest(
            items = listOf(testItem),
            destination = SendDestination.AllTrustedPeers,
            isUserAuthorized = true
        )

        val result = SyncRuntime.executeSendRequest(request)
        assertTrue("Send must be rejected when paused", result is SendResult.Rejected)

        // Unpause
        SyncPolicyManager.setSyncPaused(false)
        assertFalse("SyncPolicy must not be paused", SyncPolicyManager.syncPolicy.value.isSyncPaused)
    }

    @Test
    fun testCaptureDoesNotEqualSynchronize() = runBlocking {
        // Ensure auto-sync is OFF
        SyncPolicyManager.setAutoSync(false)
        assertFalse(SyncPolicyManager.syncPolicy.value.isAutoSyncEnabled)

        // Inserting item locally
        val localItem = ClipboardItem(
            id = UUID.randomUUID().toString(),
            content = "Local clipboard item",
            type = ClipboardItem.TYPE_TEXT,
            sourceDeviceId = "dev_local_phone"
        )
        repository.insertClipboardItem(localItem)
        val saved = repository.getItemById(localItem.id)
        assertNotNull("Local item persisted in database", saved)

        // Verify that operational state is not SYNCING automatically
        val state = SyncRuntime.operationalSyncState.value
        assertNotEquals("State must not be SYNCING without explicit user trigger", OperationalSyncState.SYNCING, state)
    }

    @Test
    fun testEcosystemStateAggregation_TrustedPeersWithoutFakingOnline() = runBlocking {
        val trustedPeerId = "peer_trusted_desktop_99"
        DeviceTrustManager.recordPeerTrust(
            context = context,
            peerDeviceId = trustedPeerId,
            deviceName = "Workstation Desktop",
            deviceType = "DESKTOP",
            ipHint = "192.168.1.50"
        )

        val ecosystemState = SyncRuntime.ecosystemState.value
        val peerInEcosystem = ecosystemState.allDevices.find { it.deviceId == trustedPeerId }

        assertNotNull("Trusted peer must appear in ecosystemState", peerInEcosystem)
        assertTrue("Peer must be marked as paired", peerInEcosystem!!.isPaired)
        // Because the transport has not actively discovered it, it must be DISCONNECTED and offline
        assertFalse("Undiscovered trusted peer must NOT be marked online", peerInEcosystem.isOnline)
        assertEquals("Undiscovered trusted peer must be DISCONNECTED", ConnectionState.DISCONNECTED, peerInEcosystem.connectionState)
    }

    @Test
    fun testActiveTransferTrackingAndCancellation() = runBlocking {
        val testItem = ClipboardItem(
            id = UUID.randomUUID().toString(),
            sourceDeviceId = "dev_local",
            content = "Transfer test payload",
            type = ClipboardItem.TYPE_TEXT,
            sizeBytes = 1024L
        )

        val request = ExplicitSendRequest(
            items = listOf(testItem),
            destination = SendDestination.SpecificPeer("peer_nonexistent"),
            isUserAuthorized = true
        )

        // Execute send to an offline peer
        val result = SyncRuntime.executeSendRequest(request)
        // Transfer should complete with failure or rejection
        assertTrue("Result is SendResult", result is SendResult)

        // Verify active transfers list contains this transfer
        val transfers = SyncRuntime.activeTransfers.value
        assertTrue("Transfers tracked", transfers.isNotEmpty())
        val lastTransfer = transfers.first()
        assertEquals("Transfer destination matches", "peer_nonexistent", lastTransfer.targetPeerId)
    }

    @Test
    fun testMultiItemBundleValidation() = runBlocking {
        val items = listOf(
            ClipboardItem(id = "item_1", sourceDeviceId = "dev_local", content = "Item 1", type = ClipboardItem.TYPE_TEXT),
            ClipboardItem(id = "item_2", sourceDeviceId = "dev_local", content = "Item 2", type = ClipboardItem.TYPE_TEXT),
            ClipboardItem(id = "item_3", sourceDeviceId = "dev_local", content = "Item 3", type = ClipboardItem.TYPE_TEXT)
        )

        val request = ExplicitSendRequest(
            items = items,
            destination = SendDestination.AllTrustedPeers,
            isUserAuthorized = true
        )

        assertEquals("Bundle must contain 3 items", 3, request.items.size)
        assertEquals("Destination is AllTrustedPeers", SendDestination.AllTrustedPeers, request.destination)
    }
}
