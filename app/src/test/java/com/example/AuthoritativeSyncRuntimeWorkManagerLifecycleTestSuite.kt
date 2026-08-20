package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.core.identity.DeviceTrustManager
import com.example.core.policy.OperationalSyncState
import com.example.core.policy.SyncPolicy
import com.example.core.policy.SyncPolicyManager
import com.example.core.policy.resolveOperationalSyncState
import com.example.data.database.ClipboardDatabase
import com.example.data.database.entity.DeliveryState
import com.example.data.database.entity.PendingClipboardDeliveryEntity
import com.example.data.model.ClipboardItem
import com.example.data.repository.ClipboardRepository
import com.example.keyboard.UniversalClipboardInputMethodService
import com.example.sync.BackgroundSyncWorker
import com.example.sync.SyncRuntime
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthoritativeSyncRuntimeWorkManagerLifecycleTestSuite {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DeviceTrustManager.init(context)
        SyncPolicyManager.init(context)
        SyncPolicyManager.setAutoSync(true)
        SyncPolicyManager.resumeSync()
        SyncRuntime.resetForTesting(context)
    }

    // =========================================================================
    // 1. RUNTIME OWNERSHIP & NO DUPLICATE RUNTIMES
    // =========================================================================

    @Test
    fun testSingleAuthoritativeSyncRuntimeSharedAcrossClients() {
        val runtime = SyncRuntime.initialize(context)

        // Create IME service without MainActivity
        val imeController = Robolectric.buildService(UniversalClipboardInputMethodService::class.java)
        val imeService = imeController.create().get()

        // Create MainViewModel
        val mainViewModel = MainViewModel(context as android.app.Application)

        // Verify both share the EXACT same authoritative instances
        assertSame("IME and Runtime must share identical SyncEngine", runtime.syncEngine, imeService.syncEngine)
        assertSame("ViewModel and Runtime must share identical SyncEngine", runtime.syncEngine, mainViewModel.syncEngine)
        assertSame("IME and ViewModel must share identical TransportManager", runtime.transportManager, mainViewModel.transportManager)
        assertSame("IME and ViewModel must share identical LocalWifiTransport", runtime.localWifiTransport, mainViewModel.localWifiTransport)

        imeController.destroy()
    }

    @Test
    fun testImeOperatesWhenMainActivityNeverCreatedOrDestroyed() {
        runBlocking {
            // MainActivity is never instantiated
            val imeController = Robolectric.buildService(UniversalClipboardInputMethodService::class.java)
            val imeService = imeController.create().get()

            assertNotNull(imeService.syncEngine)
            assertNotNull(imeService.repository)

            val item = ClipboardItem(
                id = "clip_ime_standalone_1",
                sourceDeviceId = "dev_local",
                content = "Standalone IME Clipboard Item",
                createdAt = System.currentTimeMillis()
            )
            imeService.repository?.insertClipboardItem(item)

            val retrieved = imeService.repository?.getItemById("clip_ime_standalone_1")
            assertNotNull("IME must read/write persistent repository independently", retrieved)
            assertEquals("Standalone IME Clipboard Item", retrieved?.content)

            imeController.destroy()
        }
    }

    // =========================================================================
    // 2. IME SEND & PERSISTENT PENDING DELIVERIES
    // =========================================================================

    @Test
    fun testImeSendQueuesPersistentDeliveryUsingSelectedPeerId() {
        runBlocking {
            val runtime = SyncRuntime.initialize(context)
            val targetPeerId = "dev_target_macbook_pro"
            DeviceTrustManager.recordPeerTrust(context, targetPeerId, "MacBook Pro")

            val itemToSync = ClipboardItem(
                id = "clip_ime_send_2026",
                sourceDeviceId = "dev_local",
                content = "Targeted transmission to peer",
                createdAt = System.currentTimeMillis()
            )
            runtime.repository.insertClipboardItem(itemToSync)

            // Sync to target peer (offline in test environment, so enqueues persistent delivery)
            val result = runtime.syncEngine.syncClipboardItem(itemToSync, targetPeerId)
            assertFalse("Offline target returns false on immediate send", result)

            val pendingDeliveries = runtime.repository.getAllDeliveries()
            val delivery = pendingDeliveries.find { it.clipboardItemId == "clip_ime_send_2026" }

            assertNotNull("Must create persistent delivery record in database", delivery)
            assertEquals(targetPeerId, delivery?.targetPeerDeviceId)
            assertEquals(DeliveryState.PENDING.name, delivery?.state)
        }
    }

    // =========================================================================
    // 3. WORKMANAGER INTEGRATION & PROCESS-DEATH SIMULATION
    // =========================================================================

    @Test
    fun testPendingDeliverySurvivesProcessRecreationAndWorkerRestoresWork() {
        runBlocking {
            val db = ClipboardDatabase.getInstance(context)
            val repo = ClipboardRepository(db.clipboardItemDao(), db.pendingDeliveryDao())

            val testDelivery = PendingClipboardDeliveryEntity(
                deliveryId = "deliv_restart_test_1",
                clipboardItemId = "clip_persisted_99",
                targetPeerDeviceId = "peer_remote_office",
                createdAt = System.currentTimeMillis(),
                nextAttemptAt = System.currentTimeMillis() - 1000, // Ready for retry
                attemptCount = 1,
                state = DeliveryState.PENDING.name
            )
            repo.enqueuePendingDelivery(testDelivery)

            // Simulate complete process termination by resetting in-memory SyncRuntime
            SyncRuntime.resetForTesting(null)

            // Run WorkManager BackgroundSyncWorker
            val worker = TestListenableWorkerBuilder<BackgroundSyncWorker>(context).build()
            val workerResult = worker.doWork()

            // Background worker must succeed or retry cleanly without crashing
            assertTrue(
                "Worker result should be Success or Retry",
                workerResult is ListenableWorker.Result.Success || workerResult is ListenableWorker.Result.Retry
            )

            // Verify runtime was restored and pending delivery remains intact
            val restoredDeliveries = SyncRuntime.repository.getAllDeliveries()
            assertTrue(restoredDeliveries.any { it.deliveryId == "deliv_restart_test_1" })
        }
    }

    @Test
    fun testWorkManagerRespectsPausedPolicy() {
        runBlocking {
            SyncPolicyManager.pauseSync()

            val worker = TestListenableWorkerBuilder<BackgroundSyncWorker>(context).build()
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
        }
    }

    @Test
    fun testWorkManagerRespectsLocalOnlyPolicy() {
        runBlocking {
            SyncPolicyManager.setAutoSync(false)

            val worker = TestListenableWorkerBuilder<BackgroundSyncWorker>(context).build()
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
        }
    }

    // =========================================================================
    // 4. POLICY & OPERATIONAL STATE SEMANTICS
    // =========================================================================

    @Test
    fun testPolicyAndOperationalStateSemantics() {
        // 1. AutoSync enabled + Network available + Connected peers -> SYNCING
        val activeState = resolveOperationalSyncState(
            isWifiAvailable = true,
            syncPolicy = SyncPolicy(isAutoSyncEnabled = true, isSyncPaused = false),
            connectedAuthorizedPeerCount = 1
        )
        assertEquals(OperationalSyncState.SYNCING, activeState)

        // 2. AutoSync enabled + Network lost -> OFFLINE (Policy remains AutoSync)
        val offlineState = resolveOperationalSyncState(
            isWifiAvailable = false,
            syncPolicy = SyncPolicy(isAutoSyncEnabled = true, isSyncPaused = false),
            connectedAuthorizedPeerCount = 0
        )
        assertEquals(OperationalSyncState.OFFLINE, offlineState)

        // 3. User paused sync + Network available -> PAUSED
        val pausedState = resolveOperationalSyncState(
            isWifiAvailable = true,
            syncPolicy = SyncPolicy(isAutoSyncEnabled = true, isSyncPaused = true),
            connectedAuthorizedPeerCount = 1
        )
        assertEquals(OperationalSyncState.PAUSED, pausedState)

        // 4. Local Only + Network available + Connected peers -> LOCAL_ONLY
        val localOnlyState = resolveOperationalSyncState(
            isWifiAvailable = true,
            syncPolicy = SyncPolicy(isAutoSyncEnabled = false, isSyncPaused = false),
            connectedAuthorizedPeerCount = 1
        )
        assertEquals(OperationalSyncState.LOCAL_ONLY, localOnlyState)
    }

    // =========================================================================
    // 5. RELIABILITY & ACK IDEMPOTENCY
    // =========================================================================

    @Test
    fun testAckUpdatesDeliveryStateAndDuplicateAckIsIdempotent() {
        runBlocking {
            val runtime = SyncRuntime.initialize(context)
            val repo = runtime.repository

            val deliveryId = "deliv_ack_idempotent_1"
            val delivery = PendingClipboardDeliveryEntity(
                deliveryId = deliveryId,
                clipboardItemId = "clip_item_ack_1",
                targetPeerDeviceId = "dev_peer_target",
                createdAt = System.currentTimeMillis(),
                state = DeliveryState.PENDING.name
            )
            repo.enqueuePendingDelivery(delivery)

            // First ACK
            repo.markDeliveryAcknowledged(deliveryId, System.currentTimeMillis())
            val ackedDelivery = repo.getDeliveryById(deliveryId)
            assertEquals(DeliveryState.ACKNOWLEDGED.name, ackedDelivery?.state)

            // Duplicate ACK must be handled safely without error or duplicate state
            repo.markDeliveryAcknowledged(deliveryId, System.currentTimeMillis() + 1000)
            val duplicateAckedDelivery = repo.getDeliveryById(deliveryId)
            assertEquals(DeliveryState.ACKNOWLEDGED.name, duplicateAckedDelivery?.state)
        }
    }

    // =========================================================================
    // 6. LIFECYCLE DECOUPLING & DESTRUCTION INDEPENDENCE
    // =========================================================================

    @Test
    fun testMainActivityDestructionLeavesTransportAndMonitoringAlive() {
        val runtime = SyncRuntime.initialize(context)
        val app = context as android.app.Application

        // Create MainViewModel and simulate UI active
        val viewModel = MainViewModel(app)
        assertTrue("Transport server should be available", runtime.localWifiTransport.isAvailable)
        assertNotNull("Network presence callbacks must be registered", runtime.networkPresenceMonitor.onNetworkRestored)

        // Clear ViewModel as happens when MainActivity closes
        val onClearedMethod = MainViewModel::class.java.getDeclaredMethod("onCleared")
        onClearedMethod.isAccessible = true
        onClearedMethod.invoke(viewModel)

        // Verify Authoritative SyncRuntime and transport remain completely functional
        assertTrue("Transport server must remain alive after ViewModel onCleared", runtime.localWifiTransport.isAvailable)
        assertNotNull("Network presence monitor must remain registered after ViewModel onCleared", runtime.networkPresenceMonitor.onNetworkRestored)
    }

    @Test
    fun testImeDestructionLeavesTransportAndMonitoringAlive() {
        val runtime = SyncRuntime.initialize(context)

        val imeController = Robolectric.buildService(UniversalClipboardInputMethodService::class.java)
        imeController.create().get()

        assertTrue("Transport server should be available", runtime.localWifiTransport.isAvailable)

        // Destroy IME
        imeController.destroy()

        // Verify SyncRuntime and network monitor remain active
        assertTrue("Transport server must remain alive after IME destroy", runtime.localWifiTransport.isAvailable)
        assertNotNull("Network presence monitor must remain registered after IME destroy", runtime.networkPresenceMonitor.onNetworkRestored)
    }

    @Test
    fun testNetworkRestorationTriggersReconnectAllTrustedPeers() {
        val runtime = SyncRuntime.initialize(context)
        DeviceTrustManager.recordPeerTrust(context, "peer_desktop_office", "Desktop Office")
        DeviceTrustManager.recordPeerSeen(context, "peer_desktop_office", "Desktop Office", "192.168.1.150")

        // Trigger network restoration callback on transport
        runtime.localWifiTransport.reconnectAllTrustedPeers()

        // Verify discovered devices has the trusted peer queued for reconnection
        val discovered = runtime.localWifiTransport.discoveredDevices.value
        val peer = discovered.find { it.deviceId == "peer_desktop_office" }
        assertNotNull("Trusted peer must be added to discovered devices for reconnection", peer)
        assertEquals("192.168.1.150", peer?.ipAddress)
    }

    // =========================================================================
    // 7. NO-UI BACKGROUND EXECUTION & POLICY PRESERVATION
    // =========================================================================

    @Test
    fun testMainActivityAbsentAndImeAbsentWorkManagerExecutesDirectly() {
        runBlocking {
            // Neither MainActivity nor IME ever created
            val worker = TestListenableWorkerBuilder<BackgroundSyncWorker>(context).build()
            val result = worker.doWork()

            assertTrue(
                "Worker should successfully execute when UI components are completely absent",
                result is ListenableWorker.Result.Success || result is ListenableWorker.Result.Retry
            )
        }
    }

    @Test
    fun testPolicyPreservationAcrossNetworkStateChanges() {
        // 1. AUTO_SYNC preserved during network loss and recovery
        SyncPolicyManager.setAutoSync(true)
        SyncPolicyManager.resumeSync()
        var policy = SyncPolicyManager.getPolicy()
        assertTrue(policy.isAutoSyncEnabled)
        assertFalse(policy.isSyncPaused)

        // Offline network state
        val stateOffline = resolveOperationalSyncState(
            isWifiAvailable = false,
            syncPolicy = policy,
            connectedAuthorizedPeerCount = 0
        )
        assertEquals(OperationalSyncState.OFFLINE, stateOffline)
        // Policy must not have changed to LOCAL_ONLY or PAUSED
        policy = SyncPolicyManager.getPolicy()
        assertTrue(policy.isAutoSyncEnabled)
        assertFalse(policy.isSyncPaused)

        // 2. PAUSED preserved across network recovery
        SyncPolicyManager.pauseSync()
        val pausedPolicy = SyncPolicyManager.getPolicy()
        val statePaused = resolveOperationalSyncState(
            isWifiAvailable = true,
            syncPolicy = pausedPolicy,
            connectedAuthorizedPeerCount = 1
        )
        assertEquals(OperationalSyncState.PAUSED, statePaused)

        // 3. LOCAL_ONLY preserved across network recovery
        SyncPolicyManager.resumeSync()
        SyncPolicyManager.setAutoSync(false)
        val localPolicy = SyncPolicyManager.getPolicy()
        val stateLocal = resolveOperationalSyncState(
            isWifiAvailable = true,
            syncPolicy = localPolicy,
            connectedAuthorizedPeerCount = 1
        )
        assertEquals(OperationalSyncState.LOCAL_ONLY, stateLocal)
    }

    @Test
    fun testDuplicateWorkerExecutionIsIdempotent() {
        runBlocking {
            val runtime = SyncRuntime.initialize(context)
            val testDelivery = PendingClipboardDeliveryEntity(
                deliveryId = "deliv_duplicate_worker_1",
                clipboardItemId = "clip_item_dup_1",
                targetPeerDeviceId = "peer_dup_target",
                createdAt = System.currentTimeMillis(),
                state = DeliveryState.PENDING.name
            )
            runtime.repository.enqueuePendingDelivery(testDelivery)

            val worker1 = TestListenableWorkerBuilder<BackgroundSyncWorker>(context).build()
            worker1.doWork()

            val worker2 = TestListenableWorkerBuilder<BackgroundSyncWorker>(context).build()
            worker2.doWork()

            // Verify delivery entity was not duplicated
            val all = runtime.repository.getAllDeliveries().filter { it.deliveryId == "deliv_duplicate_worker_1" }
            assertEquals("Delivery record must not be duplicated by multiple workers", 1, all.size)
        }
    }
}
