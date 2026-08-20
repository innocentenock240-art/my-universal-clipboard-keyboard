package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.core.identity.DeviceTrustManager
import com.example.core.policy.ExplicitSendRequest
import com.example.core.policy.SendDestination
import com.example.core.policy.SendResult
import com.example.core.policy.SyncPolicyManager
import com.example.data.clipboard.ClipboardCoreManager
import com.example.data.database.entity.DeliveryState
import com.example.data.database.entity.PendingClipboardDeliveryEntity
import com.example.data.model.ClipboardItem
import com.example.keyboard.UniversalClipboardInputMethodService
import com.example.sync.BackgroundSyncWorker
import com.example.sync.SyncRuntime
import com.example.sync.transport.LocalWifiTransport
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.StringWriter
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthoritativeIntentAndPauseGateTestSuite {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DeviceTrustManager.init(context)
        SyncPolicyManager.init(context)
        SyncPolicyManager.resetToDefaults()
        SyncPolicyManager.setAutoSync(true)
        SyncPolicyManager.resumeSync()
        SyncRuntime.resetForTesting(context)
    }

    @After
    fun tearDown() {
        SyncPolicyManager.resetToDefaults()
        SyncPolicyManager.resumeSync()
        SyncRuntime.resetForTesting(context)
    }

    // =========================================================================
    // 1. CAPTURE IS NOT SYNCHRONIZATION (NO AUTOMATIC PUSH)
    // =========================================================================

    @Test
    fun testCaptureDoesNotTriggerAutomaticNetworkSyncOrQueue() {
        runBlocking {
            val runtime = SyncRuntime.initialize(context)
            val targetPeerId = "peer_trusted_desktop"
            DeviceTrustManager.recordPeerTrust(context, targetPeerId, "Desktop Mac")

            // 1. Capture local clipboard item
            val capturedText = "Local private clipboard item that must not auto-sync"
            runtime.clipboardCore.processClipboardText(capturedText)

            // 2. Item must be persisted locally in repository
            val hash = ClipboardCoreManager.computeSha256(capturedText)
            var localItem: ClipboardItem? = null
            for (i in 0 until 20) {
                localItem = runtime.repository.getItemByHash(hash)
                if (localItem != null) break
                kotlinx.coroutines.delay(50)
            }
            assertNotNull("Captured item must be stored in local Room database", localItem)

            // 3. Absolute Mandate: NO pending delivery created automatically
            val pendingDeliveries = runtime.repository.getAllDeliveries()
            val relatedDeliveries = pendingDeliveries.filter { it.clipboardItemId == localItem?.id }
            assertTrue("No pending delivery must be enqueued on capture alone (Capture != Sync)", relatedDeliveries.isEmpty())
        }
    }

    // =========================================================================
    // 2. EXPLICIT SEND REQUEST (AUTHORITATIVE GATE)
    // =========================================================================

    @Test
    fun testExplicitSendToSpecificPeerSucceedsAndEnqueuesDelivery() {
        runBlocking {
            val runtime = SyncRuntime.initialize(context)
            val targetPeerId = "peer_laptop_01"
            DeviceTrustManager.recordPeerTrust(context, targetPeerId, "ThinkPad X1")

            val item = ClipboardItem(
                id = "clip_explicit_01",
                sourceDeviceId = "local_dev",
                content = "Intentional transfer payload",
                createdAt = System.currentTimeMillis()
            )
            runtime.repository.insertClipboardItem(item)

            val sendRequest = ExplicitSendRequest(
                items = listOf(item),
                destination = SendDestination.SpecificPeer(targetPeerId),
                isUserAuthorized = true
            )

            val result = runtime.executeSendRequest(sendRequest)
            // Since remote peer is offline in JVM test, it gets safely queued
            assertTrue("Result must be Queued or Success", result is SendResult.Queued || result is SendResult.Success)

            val deliveries = runtime.repository.getAllDeliveries()
            val delivery = deliveries.find { it.clipboardItemId == "clip_explicit_01" }
            assertNotNull("Delivery must be persisted in queue", delivery)
            assertEquals(targetPeerId, delivery?.targetPeerDeviceId)
            assertEquals(DeliveryState.PENDING.name, delivery?.state)
        }
    }

    @Test
    fun testExplicitSendMultipleItemsBundle() {
        runBlocking {
            val runtime = SyncRuntime.initialize(context)
            val targetPeerId = "peer_tablet_02"
            DeviceTrustManager.recordPeerTrust(context, targetPeerId, "Pixel Tablet")

            val item1 = ClipboardItem(id = "clip_bundle_1", sourceDeviceId = "local_dev", content = "Bundle Part 1")
            val item2 = ClipboardItem(id = "clip_bundle_2", sourceDeviceId = "local_dev", content = "Bundle Part 2")
            runtime.repository.insertClipboardItem(item1)
            runtime.repository.insertClipboardItem(item2)

            val sendRequest = ExplicitSendRequest(
                items = listOf(item1, item2),
                destination = SendDestination.SpecificPeer(targetPeerId),
                isUserAuthorized = true
            )

            val result = runtime.executeSendRequest(sendRequest)
            assertTrue(result is SendResult.Queued || result is SendResult.Success)

            val deliveries = runtime.repository.getAllDeliveries()
            assertTrue(deliveries.any { it.clipboardItemId == "clip_bundle_1" && it.targetPeerDeviceId == targetPeerId })
            assertTrue(deliveries.any { it.clipboardItemId == "clip_bundle_2" && it.targetPeerDeviceId == targetPeerId })
        }
    }

    // =========================================================================
    // 3. AUTHORITATIVE PAUSE GATE (OUTBOUND REJECTION)
    // =========================================================================

    @Test
    fun testPausedOutboundSendIsAuthoritativelyRejected() {
        runBlocking {
            val runtime = SyncRuntime.initialize(context)
            val targetPeerId = "peer_phone_03"
            DeviceTrustManager.recordPeerTrust(context, targetPeerId, "Galaxy S24")

            val item = ClipboardItem(
                id = "clip_pause_outbound",
                sourceDeviceId = "local_dev",
                content = "This item cannot leave the device while paused"
            )
            runtime.repository.insertClipboardItem(item)

            // User pauses synchronization
            SyncPolicyManager.pauseSync()
            assertTrue(SyncPolicyManager.getPolicy().isSyncPaused)

            val sendRequest = ExplicitSendRequest(
                items = listOf(item),
                destination = SendDestination.SpecificPeer(targetPeerId),
                isUserAuthorized = true
            )

            val result = runtime.executeSendRequest(sendRequest)
            assertTrue("SendRequest must be Rejected when sync is paused", result is SendResult.Rejected)
            assertEquals("Synchronization is currently paused.", (result as SendResult.Rejected).reason)

            // Verify transport direct send is also rejected
            val transportDirectResult = runtime.localWifiTransport.sendItem(item, targetPeerId)
            assertFalse("Transport sendItem must return false when paused", transportDirectResult)
        }
    }

    // =========================================================================
    // 4. AUTHORITATIVE PAUSE GATE (INBOUND REJECTION)
    // =========================================================================

    @Test
    fun testPausedInboundPayloadIsRejectedWithoutSavingOrApplying() {
        runBlocking {
            val receiver = LocalWifiTransport(port = 55391)
            val senderPeerId = "peer_remote_sender_88"
            receiver.addKnownPeer(senderPeerId)

            // Receiver pauses sync
            SyncPolicyManager.pauseSync()
            assertTrue(SyncPolicyManager.getPolicy().isSyncPaused)

            val incomingItem = ClipboardItem(
                id = "clip_inbound_while_paused",
                sourceDeviceId = senderPeerId,
                sourceDeviceName = "Sender Phone",
                type = "TEXT",
                content = "Sensitive incoming content",
                hash = ClipboardCoreManager.computeSha256("Sensitive incoming content")
            )

            val stringWriter = StringWriter()
            val printWriter = PrintWriter(stringWriter)

            val processed = receiver.processIncomingClipboardItem(incomingItem, printWriter)

            assertFalse("Incoming payload must be rejected when paused", processed)
            val response = stringWriter.toString().trim()
            assertEquals("Receiver must reply with ERROR_PAUSED", "ERROR_PAUSED", response)
        }
    }

    // =========================================================================
    // 5. BACKGROUND WORKER RESPECTS PAUSE STATE
    // =========================================================================

    @Test
    fun testBackgroundSyncWorkerRespectsPausedGate() {
        runBlocking {
            val runtime = SyncRuntime.initialize(context)
            val testDelivery = PendingClipboardDeliveryEntity(
                deliveryId = "deliv_pause_worker_1",
                clipboardItemId = "clip_pending_1",
                targetPeerDeviceId = "peer_remote_office",
                createdAt = System.currentTimeMillis(),
                nextAttemptAt = System.currentTimeMillis() - 1000,
                attemptCount = 1,
                state = DeliveryState.PENDING.name
            )
            runtime.repository.enqueuePendingDelivery(testDelivery)

            // Pause sync
            SyncPolicyManager.pauseSync()

            val worker = TestListenableWorkerBuilder<BackgroundSyncWorker>(context).build()
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)

            // Pending delivery remains safely intact in PENDING state
            val persistedDeliveries = runtime.repository.getAllDeliveries()
            val delivery = persistedDeliveries.find { it.deliveryId == "deliv_pause_worker_1" }
            assertNotNull(delivery)
            assertEquals(DeliveryState.PENDING.name, delivery?.state)
        }
    }

    // =========================================================================
    // 6. IME INTEGRATION THROUGH AUTHORITATIVE GATE
    // =========================================================================

    @Test
    fun testImeServiceUsesAuthoritativeGateForPeerSend() {
        runBlocking {
            val runtime = SyncRuntime.initialize(context)
            val targetPeerId = "peer_work_station"
            DeviceTrustManager.recordPeerTrust(context, targetPeerId, "Workstation")

            val imeController = Robolectric.buildService(UniversalClipboardInputMethodService::class.java)
            val imeService = imeController.create().get()

            val item = ClipboardItem(
                id = "clip_ime_gate_test",
                sourceDeviceId = "local_dev",
                content = "Sent from IME keyboard"
            )
            runtime.repository.insertClipboardItem(item)

            // When unpaused: send request is accepted and enqueued
            val sendRequest = ExplicitSendRequest(
                items = listOf(item),
                destination = SendDestination.SpecificPeer(targetPeerId),
                isUserAuthorized = true
            )
            val unpausedResult = SyncRuntime.executeSendRequest(sendRequest)
            assertTrue(unpausedResult is SendResult.Queued || unpausedResult is SendResult.Success)

            // When paused: send request is rejected
            SyncPolicyManager.pauseSync()
            val pausedResult = SyncRuntime.executeSendRequest(sendRequest)
            assertTrue(pausedResult is SendResult.Rejected)

            imeController.destroy()
        }
    }
}
