package com.example.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.core.identity.DeviceIdentityManager
import com.example.core.identity.DeviceTrustManager
import com.example.core.policy.ExplicitSendRequest
import com.example.core.policy.OperationalSyncState
import com.example.core.policy.SendResult
import com.example.core.policy.SyncPolicy
import com.example.core.policy.SyncPolicyManager
import com.example.core.policy.resolveOperationalSyncState
import com.example.core.transport.NetworkPresenceMonitor
import com.example.core.transport.TransportManager
import com.example.data.clipboard.ClipboardCoreManager
import com.example.data.database.ClipboardDatabase
import com.example.data.model.ConnectionState
import com.example.data.model.Device
import com.example.data.repository.ClipboardRepository
import com.example.sync.model.ActiveTransfer
import com.example.sync.model.EcosystemState
import com.example.sync.model.TransferStatus
import com.example.sync.transport.BluetoothTransportAdapter
import com.example.sync.transport.LocalWifiTransport
import com.example.sync.transport.WifiDirectTransportAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Authoritative Application/Process-level Shared Synchronization Runtime.
 * 
 * Provides a single source of truth for:
 * - SyncEngine
 * - TransportManager & Transport Adapters (LocalWifiTransport, Bluetooth, Wi-Fi Direct)
 * - ClipboardRepository & SQLite Room Database
 * - ClipboardCoreManager (System Clipboard Listener)
 * - NetworkPresenceMonitor
 * - DeviceTrustManager & SyncPolicyManager
 * 
 * Consumed by:
 * - Main application UI (MainViewModel)
 * - Android InputMethodService (UniversalClipboardInputMethodService)
 * - Background Lifecycle Workers (BackgroundSyncWorker / WorkManager)
 */
object SyncRuntime {
    private const val TAG = "SyncRuntime"
    const val UNIQUE_ONE_TIME_SYNC_WORK = "UniversalClipboard_PendingDeliveryWork"
    const val UNIQUE_PERIODIC_SYNC_WORK = "UniversalClipboard_PeriodicSyncWork"

    private val runtimeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var incomingCollectorJob: Job? = null

    @Volatile
    private var isInitialized = false

    lateinit var appContext: Context
        private set

    lateinit var database: ClipboardDatabase
        private set

    lateinit var repository: ClipboardRepository
        private set

    lateinit var clipboardCore: ClipboardCoreManager
        private set

    lateinit var networkPresenceMonitor: NetworkPresenceMonitor
        private set

    lateinit var localWifiTransport: LocalWifiTransport
        private set

    lateinit var bluetoothTransport: BluetoothTransportAdapter
        private set

    lateinit var wifiDirectTransport: WifiDirectTransportAdapter
        private set

    lateinit var transportManager: TransportManager
        private set

    lateinit var syncEngine: SyncEngine
        private set

    lateinit var operationalSyncState: StateFlow<OperationalSyncState>
        private set

    private val _activeTransfers = MutableStateFlow<List<ActiveTransfer>>(emptyList())
    val activeTransfers: StateFlow<List<ActiveTransfer>> = _activeTransfers.asStateFlow()

    private val transferJobs = ConcurrentHashMap<String, Job>()

    lateinit var ecosystemState: StateFlow<EcosystemState>
        private set

    @Synchronized
    fun initialize(context: Context): SyncRuntime {
        val app = context.applicationContext ?: context
        if (isInitialized) {
            val currentId = DeviceIdentityManager.getLocalDeviceId(app)
            if (::ecosystemState.isInitialized && ecosystemState.value.localDevice.deviceId != currentId) {
                resetForTesting(app)
            }
            return this
        }

        appContext = app

        // 1. Initialize persistent identity and policies
        SyncPolicyManager.init(app)
        DeviceTrustManager.init(app)
        val initialLocalDevice = DeviceIdentityManager.getLocalDevice(app)

        // 2. Initialize network presence monitor
        networkPresenceMonitor = NetworkPresenceMonitor(app)

        // 3. Initialize persistent database & repository
        database = ClipboardDatabase.getInstance(app)
        repository = ClipboardRepository(database.clipboardItemDao(), database.pendingDeliveryDao())
        clipboardCore = ClipboardCoreManager.getInstance(app, repository)

        // 4. Initialize single authoritative transport instances
        localWifiTransport = LocalWifiTransport(context = app)
        bluetoothTransport = BluetoothTransportAdapter()
        wifiDirectTransport = WifiDirectTransportAdapter()

        transportManager = TransportManager(
            listOf(localWifiTransport, bluetoothTransport, wifiDirectTransport)
        )

        // 5. Initialize authoritative SyncEngine
        syncEngine = SyncEngine(transportManager, repository)

        // 6. Configure Network Presence lifecycle hooks on authoritative runtime
        networkPresenceMonitor.onNetworkLost = {
            Log.i(TAG, "Network lost event: updating transport state")
            localWifiTransport.onNetworkLost()
        }
        networkPresenceMonitor.onNetworkRestored = {
            Log.i(TAG, "Network restored event: checking policy and restoring transport & deliveries")
            val policy = SyncPolicyManager.getPolicy()
            if (!policy.isSyncPaused && policy.isAutoSyncEnabled) {
                runtimeScope.launch {
                    localWifiTransport.startServer()
                    localWifiTransport.startDiscovery()
                    localWifiTransport.reconnectAllTrustedPeers()
                    triggerBackgroundDeliveryFlush(app)
                }
            }
        }
        networkPresenceMonitor.startMonitoring()

        // 7. Resolve authoritative operational state stream
        val rawOperationalSyncState = combine(
            networkPresenceMonitor.isWifiAvailable,
            SyncPolicyManager.syncPolicy,
            localWifiTransport.discoveredDevices
        ) { isWifiAvail, policy, discoveredList ->
            val connectedPeerCount = discoveredList.count { it.connectionState == ConnectionState.CONNECTED }
            resolveOperationalSyncState(
                isWifiAvailable = isWifiAvail,
                syncPolicy = policy,
                connectedAuthorizedPeerCount = connectedPeerCount
            )
        }.stateIn(
            scope = runtimeScope,
            started = SharingStarted.Eagerly,
            initialValue = OperationalSyncState.OFFLINE
        )
        operationalSyncState = AuthoritativeOperationalStateFlow(
            isWifiAvailable = networkPresenceMonitor.isWifiAvailable,
            syncPolicy = SyncPolicyManager.syncPolicy,
            discoveredDevices = localWifiTransport.discoveredDevices,
            backingFlow = rawOperationalSyncState
        )

        // 7b. Resolve Authoritative Ecosystem State stream (The single source of truth for all UI layers)
        val rawEcosystemState = combine(
            networkPresenceMonitor.isWifiAvailable,
            SyncPolicyManager.syncPolicy,
            localWifiTransport.discoveredDevices,
            _activeTransfers,
            DeviceTrustManager.trustedPeersState
        ) { isWifiAvail, policy, discoveredList, activeTransfersList, trustedRecords ->
            val localDev = DeviceIdentityManager.getLocalDevice(app).copy(
                isOnline = isWifiAvail,
                connectionState = if (isWifiAvail) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED
            )
            val discoveredMap = discoveredList.associateBy { it.deviceId }
            val trustedDevices = trustedRecords.map { record ->
                val livePeer = discoveredMap[record.peerDeviceId]
                Device(
                    deviceId = record.peerDeviceId,
                    deviceName = record.deviceName,
                    deviceType = record.deviceType,
                    ipAddress = livePeer?.ipAddress ?: record.lastKnownIpAddress,
                    isLocalDevice = false,
                    isOnline = livePeer?.connectionState == ConnectionState.CONNECTED,
                    isPaired = true,
                    connectionState = livePeer?.connectionState ?: ConnectionState.DISCONNECTED,
                    lastSeen = livePeer?.lastSeen ?: record.lastSeenTimestamp
                )
            }

            val connectedPeerCount = discoveredList.count { it.connectionState == ConnectionState.CONNECTED }
            val opState = resolveOperationalSyncState(
                isWifiAvailable = isWifiAvail,
                syncPolicy = policy,
                connectedAuthorizedPeerCount = connectedPeerCount
            )

            EcosystemState(
                localDevice = localDev,
                isWifiAvailable = isWifiAvail,
                operationalSyncState = opState,
                syncPolicy = policy,
                trustedPeers = trustedDevices,
                discoveredPeers = discoveredList,
                activeTransfers = activeTransfersList
            )
        }.stateIn(
            scope = runtimeScope,
            started = SharingStarted.Eagerly,
            initialValue = run {
                val initialTrusted = DeviceTrustManager.getAllTrustedPeers().map { record ->
                    Device(
                        deviceId = record.peerDeviceId,
                        deviceName = record.deviceName,
                        deviceType = record.deviceType,
                        ipAddress = record.lastKnownIpAddress,
                        isLocalDevice = false,
                        isOnline = false,
                        isPaired = true,
                        connectionState = ConnectionState.DISCONNECTED,
                        lastSeen = record.lastSeenTimestamp
                    )
                }
                EcosystemState(
                    localDevice = initialLocalDevice,
                    isWifiAvailable = false,
                    operationalSyncState = OperationalSyncState.OFFLINE,
                    syncPolicy = SyncPolicyManager.getPolicy(),
                    trustedPeers = initialTrusted
                )
            }
        )
        ecosystemState = AuthoritativeEcosystemStateFlow(
            isWifiAvailable = networkPresenceMonitor.isWifiAvailable,
            syncPolicy = SyncPolicyManager.syncPolicy,
            discoveredDevices = localWifiTransport.discoveredDevices,
            activeTransfers = _activeTransfers,
            trustedPeersState = DeviceTrustManager.trustedPeersState,
            backingFlow = rawEcosystemState,
            app = app
        )

        // 8. Capture is strictly local: Local clipboard changes are stored in repository and deduplicated locally.
        // NON-NEGOTIABLE ARCHITECTURAL RULE: CAPTURE != SYNCHRONIZATION.
        // A local clipboard event must NEVER automatically cause outbound network transmission.
        clipboardCore.onItemProcessedListener = { item ->
            Log.d(TAG, "Local clipboard item processed and stored locally [ID: ${item.id}, HashPrefix: ${item.hash.take(8)}]. Capture is strictly local; no automatic sync.")
        }

        // 9. Subscribe to unified incoming item stream: persist and copy to system clipboard
        incomingCollectorJob?.cancel()
        incomingCollectorJob = runtimeScope.launch {
            syncEngine.observeIncomingItems().collect { incomingItem ->
                try {
                    val policy = SyncPolicyManager.getPolicy()
                    if (policy.isSyncPaused) {
                        Log.w(TAG, "Dropped incoming item [${incomingItem.id}]: local synchronization is PAUSED.")
                        return@collect
                    }
                    if (policy.blockedDeviceIds.contains(incomingItem.sourceDeviceId)) {
                        Log.w(TAG, "Dropped incoming item from blocked device: ${incomingItem.sourceDeviceId}")
                        return@collect
                    }
                    // INBOUND CLIPBOARD SAFETY: Write verified content to local repository/history.
                    // DO NOT silently overwrite the user's active OS system clipboard.
                    repository.insertClipboardItem(incomingItem)
                } catch (e: Throwable) {
                    Log.e(TAG, "Error processing incoming clipboard item", e)
                }
            }
        }

        // 10. Start active transport server & discovery if policy permits
        val initialPolicy = SyncPolicyManager.getPolicy()
        if (!initialPolicy.isSyncPaused && initialPolicy.isAutoSyncEnabled) {
            localWifiTransport.startServer()
            runtimeScope.launch {
                localWifiTransport.startDiscovery()
                localWifiTransport.reconnectAllTrustedPeers()
            }
        }

        // 11. Register background maintenance periodic work
        schedulePeriodicBackgroundSync(app)

        isInitialized = true
        Log.i(TAG, "Authoritative SyncRuntime initialized successfully.")
        return this
    }

    /**
     * Schedules a one-time WorkManager job to flush pending deliveries in the background.
     */
    fun triggerBackgroundDeliveryFlush(context: Context) {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<BackgroundSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_ONE_TIME_SYNC_WORK,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
            Log.d(TAG, "Enqueued background delivery flush WorkRequest")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to enqueue background work request", e)
        }
    }

    /**
     * Schedules recurring periodic background synchronization.
     */
    fun schedulePeriodicBackgroundSync(context: Context) {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val periodicRequest = PeriodicWorkRequestBuilder<BackgroundSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_SYNC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to enqueue periodic background sync", e)
        }
    }

    /**
     * Executes an explicit user send request via the Authoritative Synchronization Gate.
     * Records real active transfer progress and status in the authoritative EcosystemState.
     */
    suspend fun executeSendRequest(request: ExplicitSendRequest): SendResult {
        val policy = SyncPolicyManager.getPolicy()
        if (policy.isSyncPaused) {
            return SendResult.Rejected(request.requestId, "Synchronization is currently paused.")
        }
        val targetName = when (val dest = request.destination) {
            is com.example.core.policy.SendDestination.SpecificPeer -> {
                val record = DeviceTrustManager.getTrustedPeer(dest.peerDeviceId)
                record?.deviceName ?: dest.peerDeviceId
            }
            is com.example.core.policy.SendDestination.AllTrustedPeers -> "All Trusted Devices"
        }
        val targetPeerId = when (val dest = request.destination) {
            is com.example.core.policy.SendDestination.SpecificPeer -> dest.peerDeviceId
            is com.example.core.policy.SendDestination.AllTrustedPeers -> "ALL"
        }
        val totalBytes = request.items.sumOf { it.sizeBytes.coerceAtLeast(it.content.length.toLong()) }
        val transferRecord = ActiveTransfer(
            transferId = request.requestId,
            items = request.items,
            targetPeerId = targetPeerId,
            targetPeerName = targetName,
            status = TransferStatus.PREPARING,
            bytesTransferred = 0L,
            totalBytes = totalBytes
        )
        _activeTransfers.value = _activeTransfers.value + transferRecord

        // Update to STREAMING
        _activeTransfers.value = _activeTransfers.value.map {
            if (it.transferId == request.requestId) it.copy(status = TransferStatus.STREAMING) else it
        }

        val currentJob = kotlinx.coroutines.currentCoroutineContext()[Job]
        if (currentJob != null) {
            transferJobs[request.requestId] = currentJob
        }
        try {
            val result = syncEngine.executeExplicitSendRequest(request) { bytesIncrement ->
                _activeTransfers.value = _activeTransfers.value.map {
                    if (it.transferId == request.requestId) {
                        it.copy(
                            status = TransferStatus.STREAMING,
                            bytesTransferred = (it.bytesTransferred + bytesIncrement).coerceAtMost(totalBytes)
                        )
                    } else it
                }
            }

            when (result) {
                is SendResult.Success -> {
                    _activeTransfers.value = _activeTransfers.value.map {
                        if (it.transferId == request.requestId) {
                            it.copy(
                                status = TransferStatus.COMPLETED,
                                bytesTransferred = totalBytes
                            )
                        } else it
                    }
                }
                is SendResult.Queued -> {
                    _activeTransfers.value = _activeTransfers.value.map {
                        if (it.transferId == request.requestId) {
                            it.copy(status = TransferStatus.QUEUED)
                        } else it
                    }
                }
                is SendResult.Rejected -> {
                    _activeTransfers.value = _activeTransfers.value.map {
                        if (it.transferId == request.requestId) {
                            it.copy(status = TransferStatus.FAILED, errorMessage = result.reason)
                        } else it
                    }
                }
                is SendResult.Failed -> {
                    _activeTransfers.value = _activeTransfers.value.map {
                        if (it.transferId == request.requestId) {
                            it.copy(status = TransferStatus.FAILED, errorMessage = result.error)
                        } else it
                    }
                }
            }
            return result
        } finally {
            transferJobs.remove(request.requestId)
        }
    }

    /**
     * Cancels an in-flight transfer.
     */
    fun cancelTransfer(transferId: String): Boolean {
        val job = transferJobs.remove(transferId)
        val currentList = _activeTransfers.value
        val transfer = currentList.firstOrNull { it.transferId == transferId }
        if (transfer != null && (transfer.status == TransferStatus.PREPARING || transfer.status == TransferStatus.STREAMING || transfer.status == TransferStatus.QUEUED)) {
            job?.cancel()
            _activeTransfers.value = currentList.map {
                if (it.transferId == transferId) it.copy(status = TransferStatus.CANCELLED) else it
            }
            Log.i(TAG, "Cancelled active transfer: $transferId")
            return true
        }
        return false
    }

    fun clearFinishedTransfers() {
        _activeTransfers.value = _activeTransfers.value.filter {
            it.status == TransferStatus.PREPARING || it.status == TransferStatus.STREAMING || it.status == TransferStatus.QUEUED
        }
    }

    /**
     * Reset runtime instances for isolated JVM tests.
     */
    @Synchronized
    fun resetForTesting(context: Context? = null) {
        incomingCollectorJob?.cancel()
        incomingCollectorJob = null
        _activeTransfers.value = emptyList()
        transferJobs.clear()
        if (::localWifiTransport.isInitialized) {
            try {
                localWifiTransport.stopServer()
            } catch (_: Throwable) {}
        }
        if (::networkPresenceMonitor.isInitialized) {
            try {
                networkPresenceMonitor.stopMonitoring()
            } catch (_: Throwable) {}
        }
        isInitialized = false
        if (context != null) {
            initialize(context)
        }
    }
}

/**
 * Authoritative Operational StateFlow wrapper that guarantees .value is synchronously evaluated
 * against the latest policy, network, and transport states without waiting for coroutine dispatch loops.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.InternalCoroutinesApi::class)
class AuthoritativeOperationalStateFlow(
    private val isWifiAvailable: StateFlow<Boolean>,
    private val syncPolicy: StateFlow<SyncPolicy>,
    private val discoveredDevices: StateFlow<List<Device>>,
    private val backingFlow: StateFlow<OperationalSyncState>
) : StateFlow<OperationalSyncState> {
    override val replayCache: List<OperationalSyncState>
        get() = listOf(value)

    override val value: OperationalSyncState
        get() {
            val connectedPeerCount = discoveredDevices.value.count { it.connectionState == ConnectionState.CONNECTED }
            return resolveOperationalSyncState(
                isWifiAvailable = isWifiAvailable.value,
                syncPolicy = syncPolicy.value,
                connectedAuthorizedPeerCount = connectedPeerCount
            )
        }

    override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<OperationalSyncState>): Nothing {
        backingFlow.collect(collector)
    }
}

/**
 * Authoritative Ecosystem StateFlow wrapper that guarantees .value is synchronously evaluated
 * against the latest policy, network, active transfers, and trusted peer states.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.InternalCoroutinesApi::class)
class AuthoritativeEcosystemStateFlow(
    private val isWifiAvailable: StateFlow<Boolean>,
    private val syncPolicy: StateFlow<SyncPolicy>,
    private val discoveredDevices: StateFlow<List<Device>>,
    private val activeTransfers: StateFlow<List<ActiveTransfer>>,
    private val trustedPeersState: StateFlow<List<com.example.core.identity.TrustedPeerRecord>>,
    private val backingFlow: StateFlow<EcosystemState>,
    private val app: Context
) : StateFlow<EcosystemState> {
    override val replayCache: List<EcosystemState>
        get() = listOf(value)

    override val value: EcosystemState
        get() {
            val isWifiAvail = isWifiAvailable.value
            val policy = syncPolicy.value
            val discoveredList = discoveredDevices.value
            val activeTransfersList = activeTransfers.value
            val trustedRecords = trustedPeersState.value

            val localDev = DeviceIdentityManager.getLocalDevice(app).copy(
                isOnline = isWifiAvail,
                connectionState = if (isWifiAvail) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED
            )
            val discoveredMap = discoveredList.associateBy { it.deviceId }
            val trustedDevices = trustedRecords.map { record ->
                val livePeer = discoveredMap[record.peerDeviceId]
                Device(
                    deviceId = record.peerDeviceId,
                    deviceName = record.deviceName,
                    deviceType = record.deviceType,
                    ipAddress = livePeer?.ipAddress ?: record.lastKnownIpAddress,
                    isLocalDevice = false,
                    isOnline = livePeer?.connectionState == ConnectionState.CONNECTED,
                    isPaired = true,
                    connectionState = livePeer?.connectionState ?: ConnectionState.DISCONNECTED,
                    lastSeen = livePeer?.lastSeen ?: record.lastSeenTimestamp
                )
            }

            val connectedPeerCount = discoveredList.count { it.connectionState == ConnectionState.CONNECTED }
            val opState = resolveOperationalSyncState(
                isWifiAvailable = isWifiAvail,
                syncPolicy = policy,
                connectedAuthorizedPeerCount = connectedPeerCount
            )

            return EcosystemState(
                localDevice = localDev,
                isWifiAvailable = isWifiAvail,
                operationalSyncState = opState,
                syncPolicy = policy,
                trustedPeers = trustedDevices,
                discoveredPeers = discoveredList,
                activeTransfers = activeTransfersList
            )
        }

    override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<EcosystemState>): Nothing {
        backingFlow.collect(collector)
    }
}
