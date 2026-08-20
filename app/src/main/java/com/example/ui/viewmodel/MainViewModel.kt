package com.example.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.identity.DeviceIdentityManager
import com.example.core.identity.DeviceTrustManager
import com.example.core.policy.ExplicitSendRequest
import com.example.core.policy.OperationalSyncState
import com.example.core.policy.SendDestination
import com.example.core.policy.SendResult
import com.example.core.policy.SyncPolicy
import com.example.core.policy.SyncPolicyManager
import com.example.core.policy.SyncScope
import com.example.core.policy.resolveOperationalSyncState
import com.example.core.transport.NetworkPresenceMonitor
import com.example.core.transport.TransportManager
import com.example.core.transport.TransportStatus
import com.example.data.clipboard.AndroidClipboardCaptureSource
import com.example.data.clipboard.ClipboardCoreManager
import com.example.data.database.ClipboardDatabase
import com.example.data.model.ClipboardItem
import com.example.data.model.ConnectionState
import com.example.data.model.Device
import com.example.data.repository.ClipboardRepository
import com.example.sync.SyncEngine
import com.example.sync.SyncRuntime
import com.example.sync.model.ActiveTransfer
import com.example.sync.model.EcosystemState
import com.example.sync.transport.BluetoothTransportAdapter
import com.example.sync.transport.LocalWifiTransport
import com.example.sync.transport.WifiDirectTransportAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: ClipboardRepository = run {
        SyncRuntime.initialize(application)
        SyncRuntime.repository
    },
    val localWifiTransport: LocalWifiTransport = SyncRuntime.localWifiTransport,
    val bluetoothTransport: BluetoothTransportAdapter = SyncRuntime.bluetoothTransport,
    val wifiDirectTransport: WifiDirectTransportAdapter = SyncRuntime.wifiDirectTransport,
    val transportManager: TransportManager = SyncRuntime.transportManager,
    val syncEngine: SyncEngine = SyncRuntime.syncEngine,
    val networkPresenceMonitor: NetworkPresenceMonitor = SyncRuntime.networkPresenceMonitor,
    private val clipboardCore: ClipboardCoreManager = SyncRuntime.clipboardCore
) : AndroidViewModel(application) {

    init {
        SyncRuntime.initialize(application)
    }

    private var currentLocalDevice = DeviceIdentityManager.getLocalDevice(application)

    val isWifiAvailable: StateFlow<Boolean> = networkPresenceMonitor.isWifiAvailable

    val transportStatuses: StateFlow<List<TransportStatus>> = transportManager.transportStatuses

    val syncPolicy: StateFlow<SyncPolicy> = SyncPolicyManager.syncPolicy

    private val _isWifiServerRunning = MutableStateFlow(false)
    val isWifiServerRunning: StateFlow<Boolean> = _isWifiServerRunning.asStateFlow()

    private val _isWifiDiscovering = MutableStateFlow(false)
    val isWifiDiscovering: StateFlow<Boolean> = _isWifiDiscovering.asStateFlow()

    val discoveredDevices: StateFlow<List<Device>> = localWifiTransport.discoveredDevices

    val operationalSyncState: StateFlow<OperationalSyncState> = SyncRuntime.operationalSyncState

    private val _incomingWifiMessages = MutableStateFlow<List<String>>(emptyList())
    val incomingWifiMessages: StateFlow<List<String>> = _incomingWifiMessages.asStateFlow()

    private val _wifiLastAckResult = MutableStateFlow<String?>(null)
    val wifiLastAckResult: StateFlow<String?> = _wifiLastAckResult.asStateFlow()

    private val _isSendingWifiHandshake = MutableStateFlow(false)
    val isSendingWifiHandshake: StateFlow<Boolean> = _isSendingWifiHandshake.asStateFlow()

    val isCaptureActive: StateFlow<Boolean> = clipboardCore.isCaptureActive

    val clipboardItems: StateFlow<List<ClipboardItem>> = repository.clipboardHistory
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val ecosystemState: StateFlow<EcosystemState> = SyncRuntime.ecosystemState
    val activeTransfers: StateFlow<List<ActiveTransfer>> = SyncRuntime.activeTransfers

    val devices: StateFlow<List<Device>> = SyncRuntime.ecosystemState
        .map { it.allDevices }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = listOf(currentLocalDevice)
        )

    private val _retentionDays = MutableStateFlow(ClipboardRepository.DEFAULT_RETENTION_DAYS.toInt())
    val retentionDays: StateFlow<Int> = _retentionDays.asStateFlow()

    private val _isWifiSyncEnabled = MutableStateFlow(true)
    val isWifiSyncEnabled: StateFlow<Boolean> = _isWifiSyncEnabled.asStateFlow()

    private val _isBluetoothSyncEnabled = MutableStateFlow(true)
    val isBluetoothSyncEnabled: StateFlow<Boolean> = _isBluetoothSyncEnabled.asStateFlow()

    private val _isWifiDirectSyncEnabled = MutableStateFlow(true)
    val isWifiDirectSyncEnabled: StateFlow<Boolean> = _isWifiDirectSyncEnabled.asStateFlow()

    private val _isCloudSyncEnabled = MutableStateFlow(false)
    val isCloudSyncEnabled: StateFlow<Boolean> = _isCloudSyncEnabled.asStateFlow()

    fun cancelTransfer(transferId: String): Boolean {
        return SyncRuntime.cancelTransfer(transferId)
    }

    init {
        SyncPolicyManager.init(application)
        DeviceTrustManager.init(application)

        // Automatically prune expired clipboard items when ViewModel/Repository initializes
        viewModelScope.launch {
            try {
                repository.deleteExpiredItems()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to delete expired items on startup", e)
            }
        }
        startClipboardCapture()

        // Observe incoming wifi transport messages for diagnostic log display
        viewModelScope.launch(Dispatchers.IO) {
            localWifiTransport.incomingMessages.collect { msg ->
                _incomingWifiMessages.value = _incomingWifiMessages.value + msg
            }
        }
    }

    fun toggleAutoSync() {
        val newState = SyncPolicyManager.toggleAutoSync()
        _isWifiSyncEnabled.value = newState
    }

    fun togglePauseSync() {
        SyncPolicyManager.togglePauseSync()
    }

    fun setSyncScope(scope: com.example.core.policy.SyncScope) {
        SyncPolicyManager.setSyncScope(scope)
    }

    fun syncItemNow(item: ClipboardItem, targetDeviceId: String? = null) {
        Log.i("SyncDiagnostics", "[SYNC_PATH_1_INVOCATION] Manual Sync triggered for item ${item.id} (target: ${targetDeviceId ?: "ALL"})")
        viewModelScope.launch(Dispatchers.IO) {
            val dest = if (targetDeviceId != null && targetDeviceId.isNotBlank() && targetDeviceId != "ALL") {
                SendDestination.SpecificPeer(targetDeviceId)
            } else {
                SendDestination.AllTrustedPeers
            }
            val request = ExplicitSendRequest(
                items = listOf(item),
                destination = dest,
                isUserAuthorized = true
            )
            val result = SyncRuntime.executeSendRequest(request)
            Log.i("SyncDiagnostics", "[SYNC_PATH_RESULT] Manual sync completion result for item ${item.id}: $result")
        }
    }

    fun sendItems(items: List<ClipboardItem>, destination: SendDestination) {
        viewModelScope.launch(Dispatchers.IO) {
            val request = ExplicitSendRequest(
                items = items,
                destination = destination,
                isUserAuthorized = true
            )
            val result = SyncRuntime.executeSendRequest(request)
            Log.i("SyncDiagnostics", "[SYNC_PATH_RESULT] Batch send result for ${items.size} items: $result")
        }
    }

    fun setDeviceBlocked(deviceId: String, blocked: Boolean) {
        SyncPolicyManager.setDeviceBlocked(deviceId, blocked)
    }

    fun pairDevice(deviceId: String) {
        localWifiTransport.authorizePeer(deviceId)
    }

    fun unpairDevice(deviceId: String) {
        localWifiTransport.revokePeerAuthorization(deviceId)
    }

    fun forgetDevice(deviceId: String) {
        localWifiTransport.revokePeerAuthorization(deviceId)
        disconnectFromDevice(Device(deviceId = deviceId, deviceName = "", deviceType = ""))
    }

    fun addClipboardItem(text: String) {
        clipboardCore.processClipboardText(text)
    }

    fun addRichClipboardItem(
        type: String,
        content: String,
        mimeType: String = ClipboardItem.MIME_TEXT_PLAIN,
        fileName: String? = null,
        sizeBytes: Long = 0L
    ) {
        clipboardCore.processRichClipboardItem(type, content, mimeType, fileName, sizeBytes)
    }

    fun copyClipboardItem(item: ClipboardItem) {
        clipboardCore.applyRemoteClipboardItem(item)
    }

    fun toggleFavorite(itemId: String) {
        val currentItem = clipboardItems.value.firstOrNull { it.id == itemId } ?: return
        viewModelScope.launch {
            try {
                repository.toggleFavorite(itemId, currentItem.isFavorite)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to toggle favorite for item $itemId", e)
            }
        }
    }

    fun togglePin(itemId: String) {
        val currentItem = clipboardItems.value.firstOrNull { it.id == itemId } ?: return
        viewModelScope.launch {
            try {
                repository.togglePin(itemId, currentItem.isPinned)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to toggle pin for item $itemId", e)
            }
        }
    }

    fun deleteItem(itemId: String) {
        viewModelScope.launch {
            try {
                repository.deleteClipboardItem(itemId)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to delete item $itemId", e)
            }
        }
    }

    fun deleteItems(itemIds: List<String>) {
        viewModelScope.launch {
            try {
                repository.deleteItemsByIds(itemIds)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to bulk delete items", e)
            }
        }
    }

    fun clearAllItems() {
        viewModelScope.launch {
            try {
                repository.clearAll()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to clear all items", e)
            }
        }
    }

    fun startClipboardCapture() {
        clipboardCore.startCapture()
    }

    fun stopClipboardCapture() {
        clipboardCore.stopCapture()
    }

    fun checkClipboardNow() {
        clipboardCore.checkClipboard()
    }

    fun setRetentionDays(days: Int) {
        // Retention requirement is fixed at 7 days for the current milestone
        _retentionDays.value = ClipboardRepository.DEFAULT_RETENTION_DAYS.toInt()
    }

    fun setWifiSyncEnabled(enabled: Boolean) {
        _isWifiSyncEnabled.value = enabled
    }

    fun setBluetoothSyncEnabled(enabled: Boolean) {
        _isBluetoothSyncEnabled.value = enabled
        viewModelScope.launch {
            if (enabled) bluetoothTransport.startTransport() else bluetoothTransport.stopTransport()
            transportManager.updateStatuses()
        }
    }

    fun setWifiDirectSyncEnabled(enabled: Boolean) {
        _isWifiDirectSyncEnabled.value = enabled
        viewModelScope.launch {
            if (enabled) wifiDirectTransport.startTransport() else wifiDirectTransport.stopTransport()
            transportManager.updateStatuses()
        }
    }

    // Milestone 5.2 Local Wi-Fi Discovery Methods
    fun startWifiDiscovery() {
        _isWifiDiscovering.value = true
        viewModelScope.launch(Dispatchers.IO) {
            localWifiTransport.startDiscovery()
            _isWifiServerRunning.value = localWifiTransport.isAvailable
        }
    }

    fun stopWifiDiscovery() {
        _isWifiDiscovering.value = false
        viewModelScope.launch(Dispatchers.IO) {
            localWifiTransport.stopDiscovery()
            _isWifiServerRunning.value = localWifiTransport.isAvailable
        }
    }

    // Milestone 5.3 Peer Connection Methods
    fun connectToDevice(device: Device) {
        viewModelScope.launch(Dispatchers.IO) {
            localWifiTransport.connectToDevice(device)
        }
    }

    fun disconnectFromDevice(device: Device) {
        viewModelScope.launch(Dispatchers.IO) {
            localWifiTransport.disconnectFromDevice(device.deviceId)
        }
    }

    // Milestone 5.1 Diagnostic Methods
    fun startWifiServer() {
        viewModelScope.launch(Dispatchers.IO) {
            localWifiTransport.startServer()
            _isWifiServerRunning.value = localWifiTransport.isAvailable
        }
    }

    fun stopWifiServer() {
        viewModelScope.launch(Dispatchers.IO) {
            localWifiTransport.stopServer()
            _isWifiServerRunning.value = localWifiTransport.isAvailable
        }
    }

    fun sendHandshake(targetIp: String, message: String = "HELLO_FROM_PHONE_A", targetPort: Int = LocalWifiTransport.DEFAULT_PORT) {
        if (targetIp.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            _isSendingWifiHandshake.value = true
            _wifiLastAckResult.value = "Sending..."
            val ack = localWifiTransport.sendHandshake(
                targetIp = targetIp.trim(),
                targetPort = targetPort,
                message = message
            )
            _wifiLastAckResult.value = ack ?: "ERROR: Connection failed or timed out"
            _isSendingWifiHandshake.value = false
        }
    }

    fun clearWifiDiagnosticLogs() {
        _incomingWifiMessages.value = emptyList()
        _wifiLastAckResult.value = null
    }

    override fun onCleared() {
        super.onCleared()
        // Note: Transport lifecycle, server sockets, and network monitoring are authoritatively
        // owned by the Application-level SyncRuntime, allowing background sync and IME operation
        // to continue uninterrupted when MainActivity/MainViewModel is destroyed.
    }
}
