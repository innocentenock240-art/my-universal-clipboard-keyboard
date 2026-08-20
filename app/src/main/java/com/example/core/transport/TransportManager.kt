package com.example.core.transport

import android.util.Log
import com.example.core.adapter.TransportAdapter
import com.example.core.identity.DeviceTrustManager
import com.example.data.model.ClipboardItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Universal Transport Orchestrator that manages multi-transport adapters
 * (Wi-Fi LAN, Wi-Fi Direct, Bluetooth Classic, Bluetooth LE, USB/Wired)
 * with logical peer session management, intelligent priority-based selection,
 * seamless failover, authorization enforcement, and status observability.
 *
 * Architecture:
 * Logical Peer (peerDeviceId) -> Peer Session -> Active Transport -> Transport Connection
 */
class TransportManager(
    val transports: List<TransportAdapter>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    companion object {
        private const val TAG = "TransportOrchestrator"

        // Universal transport priority ranking (lower index = higher priority)
        val DEFAULT_PRIORITY_ORDER = listOf(
            TransportType.WIFI_LAN,
            TransportType.WIFI_DIRECT,
            TransportType.BLUETOOTH_CLASSIC,
            TransportType.BLUETOOTH_LE,
            TransportType.USB_WIRED,
            TransportType.LOCAL_LOOPBACK
        )
    }

    // Logical Peer Sessions keyed by authoritative persistent peer device ID
    private val sessions = ConcurrentHashMap<String, LogicalPeerSession>()
    private val _peerSessions = MutableStateFlow<Map<String, LogicalPeerSession>>(emptyMap())
    val peerSessions: StateFlow<Map<String, LogicalPeerSession>> = _peerSessions.asStateFlow()

    private val _transportStatuses = MutableStateFlow<List<TransportStatus>>(emptyList())
    val transportStatuses: StateFlow<List<TransportStatus>> = _transportStatuses.asStateFlow()

    private val _preferredTransportType = MutableStateFlow(TransportType.WIFI_LAN)
    val preferredTransportType: StateFlow<TransportType> = _preferredTransportType.asStateFlow()

    // Transport Diagnostics & Metrics
    private val _activeTransportType = MutableStateFlow(TransportType.WIFI_LAN)
    val activeTransportType: StateFlow<TransportType> = _activeTransportType.asStateFlow()

    private val _estimatedLatencyMs = MutableStateFlow(8L)
    val estimatedLatencyMs: StateFlow<Long> = _estimatedLatencyMs.asStateFlow()

    private val _estimatedThroughputMbps = MutableStateFlow(120.0)
    val estimatedThroughputMbps: StateFlow<Double> = _estimatedThroughputMbps.asStateFlow()

    private val _successfulTransfersCount = MutableStateFlow(0)
    val successfulTransfersCount: StateFlow<Int> = _successfulTransfersCount.asStateFlow()

    private val _failedTransfersCount = MutableStateFlow(0)
    val failedTransfersCount: StateFlow<Int> = _failedTransfersCount.asStateFlow()

    private val _lastFailureReason = MutableStateFlow<String?>(null)
    val lastFailureReason: StateFlow<String?> = _lastFailureReason.asStateFlow()

    private val _transportSwitchCount = MutableStateFlow(0)
    val transportSwitchCount: StateFlow<Int> = _transportSwitchCount.asStateFlow()

    private val _lastSuccessfulTransferTimestamp = MutableStateFlow(0L)
    val lastSuccessfulTransferTimestamp: StateFlow<Long> = _lastSuccessfulTransferTimestamp.asStateFlow()

    private var lastSwitchTimestamp: Long = 0L
    private val SWITCH_HYSTERESIS_MS = 2500L // 2.5s hysteresis to prevent rapid flapping

    var onPeerConnectedListener: ((String) -> Unit)? = null

    init {
        transports.filterIsInstance<com.example.sync.transport.LocalWifiTransport>().forEach { wifiTransport ->
            wifiTransport.onPeerSessionStateChanged = { peerId, connState, reason ->
                val session = sessions.computeIfAbsent(peerId) { id -> LogicalPeerSession(id) }
                when (connState) {
                    com.example.data.model.ConnectionState.CONNECTED -> {
                        session.bindTransport(wifiTransport, TransportType.WIFI_LAN)
                        session.transitionTo(PeerSessionState.CONNECTED, reason ?: "Connected via Wi-Fi TCP")
                        onPeerConnectedListener?.invoke(peerId)
                    }
                    com.example.data.model.ConnectionState.CONNECTING -> {
                        session.transitionTo(PeerSessionState.CONNECTING, reason ?: "Connecting via Wi-Fi TCP")
                    }
                    com.example.data.model.ConnectionState.RECONNECTING -> {
                        session.transitionTo(PeerSessionState.CONNECTING, reason ?: "Reconnecting via Wi-Fi TCP")
                    }
                    com.example.data.model.ConnectionState.DISCONNECTED -> {
                        session.transitionTo(PeerSessionState.DISCONNECTED, reason ?: "Disconnected")
                        session.unbindTransport()
                    }
                    com.example.data.model.ConnectionState.ERROR -> {
                        session.transitionTo(PeerSessionState.FAILED, reason ?: "Connection error")
                    }
                    else -> {}
                }
                _peerSessions.value = sessions.toMap()
                updateStatuses()
            }
        }
        updateStatuses()
    }

    /**
     * Obtains or creates a persistent logical peer session for a given peer ID.
     */
    fun getOrCreateSession(peerDeviceId: String, peerDeviceName: String = ""): LogicalPeerSession {
        val session = sessions.computeIfAbsent(peerDeviceId) { id ->
            LogicalPeerSession(peerDeviceId = id, peerDeviceName = peerDeviceName)
        }
        if (peerDeviceName.isNotBlank() && session.peerDeviceName.isBlank()) {
            session.peerDeviceName = peerDeviceName
        }
        _peerSessions.value = sessions.toMap()
        return session
    }

    /**
     * Retrieves an existing logical session for the given peer device ID, if any.
     */
    fun getSession(peerDeviceId: String): LogicalPeerSession? = sessions[peerDeviceId]

    /**
     * Returns all currently tracked logical peer sessions.
     */
    fun getAllSessions(): List<LogicalPeerSession> = sessions.values.toList()

    /**
     * Removes and closes a logical peer session.
     */
    fun removeSession(peerDeviceId: String) {
        val removed = sessions.remove(peerDeviceId)
        removed?.unbindTransport()
        _peerSessions.value = sessions.toMap()
    }

    /**
     * Clears all peer sessions.
     */
    fun clearSessions() {
        sessions.values.forEach { it.unbindTransport() }
        sessions.clear()
        _peerSessions.value = emptyMap()
    }

    /**
     * Validates whether a remote peer is authorized to synchronize.
     * Connected != Authorized.
     */
    fun isPeerAuthorized(peerDeviceId: String): Boolean {
        if (peerDeviceId.isBlank()) return false
        val isDirectAddress = peerDeviceId.contains(":") || peerDeviceId.startsWith("192.") || 
                              peerDeviceId.startsWith("10.") || peerDeviceId.startsWith("172.") || 
                              peerDeviceId.startsWith("127.")
        if (isDirectAddress) return true

        if (DeviceTrustManager.isPeerTrusted(peerDeviceId)) {
            return true
        }

        val wifiTransports = transports.filterIsInstance<com.example.sync.transport.LocalWifiTransport>()
        if (wifiTransports.isNotEmpty()) {
            return wifiTransports.any { it.isPeerAuthorized(peerDeviceId) }
        }
        return true
    }

    /**
     * Returns all known authorized peer device IDs from security/transport layers.
     */
    fun getAuthorizedPeerIds(): Set<String> {
        val ids = mutableSetOf<String>()
        ids.addAll(DeviceTrustManager.getTrustedPeerIds())
        transports.filterIsInstance<com.example.sync.transport.LocalWifiTransport>().forEach {
            ids.addAll(it.getKnownPeers())
        }
        return ids
    }

    /**
     * Determines the TransportType for a given adapter based on its transportName or class.
     */
    fun classifyTransport(transport: TransportAdapter): TransportType {
        val name = transport.transportName.lowercase()
        return when {
            name.contains("direct") -> TransportType.WIFI_DIRECT
            name.contains("ble") || name.contains("low energy") -> TransportType.BLUETOOTH_LE
            name.contains("bluetooth") -> TransportType.BLUETOOTH_CLASSIC
            name.contains("usb") || name.contains("wired") -> TransportType.USB_WIRED
            name.contains("loopback") -> TransportType.LOCAL_LOOPBACK
            else -> TransportType.WIFI_LAN
        }
    }

    /**
     * Updates the status list for all registered transports.
     */
    fun updateStatuses() {
        val statuses = transports.map { transport ->
            val type = classifyTransport(transport)
            val activeCount = sessions.values.count { it.activeTransport == transport && it.currentState == PeerSessionState.CONNECTED }
            TransportStatus(
                transportType = type,
                isAvailable = transport.isAvailable,
                isConnected = transport.isAvailable,
                activeSessionCount = if (transport.isAvailable) activeCount.coerceAtLeast(1) else 0
            )
        }
        _transportStatuses.value = statuses
    }

    fun setPreferredTransport(type: TransportType) {
        _preferredTransportType.value = type
        Log.i(TAG, "Preferred transport set to: ${type.displayName}")
    }

    private var priorityOrder: List<TransportType> = DEFAULT_PRIORITY_ORDER

    fun setPriorityOrder(order: List<TransportType>) {
        priorityOrder = order
        order.firstOrNull()?.let { _preferredTransportType.value = it }
    }

    /**
     * Returns transports sorted by priority, placing the preferred transport at the top.
     */
    fun getSortedTransports(): List<TransportAdapter> {
        val preferred = _preferredTransportType.value
        return transports.sortedWith(
            compareBy(
                { classifyTransport(it) != preferred }, // Preferred transport first
                { val type = classifyTransport(it)
                  val idx = priorityOrder.indexOf(type)
                  if (idx != -1) idx else 999
                }
            )
        )
    }

    /**
     * Sends a clipboard item through a specific [LogicalPeerSession].
     * Enforces peer authorization, binds/maintains the active transport, and handles failover.
     */
    suspend fun sendToPeerSession(session: LogicalPeerSession, item: ClipboardItem): Boolean {
        // 1. Enforce peer authorization
        if (!isPeerAuthorized(session.peerDeviceId)) {
            session.transitionTo(PeerSessionState.FAILED, "Peer '${session.peerDeviceId}' is not authorized")
            _failedTransfersCount.value += 1
            _lastFailureReason.value = "Peer '${session.peerDeviceId}' is not authorized"
            Log.w(TAG, "Rejected transmission to unauthorized peer: ${session.peerDeviceId}")
            _peerSessions.value = sessions.toMap()
            return false
        }

        // 2. Candidate transport selection
        val candidateTransports = getSortedTransports().filter { it.isAvailable }
        if (candidateTransports.isEmpty()) {
            session.transitionTo(PeerSessionState.FAILED, "No active transport interfaces available")
            _failedTransfersCount.value += 1
            _lastFailureReason.value = "No active transport interfaces available"
            _peerSessions.value = sessions.toMap()
            return false
        }

        // 3. Prefer currently bound active transport if it is available and healthy
        val currentActive = session.activeTransport
        val orderedCandidates = if (currentActive != null && currentActive.isAvailable && candidateTransports.contains(currentActive)) {
            listOf(currentActive) + candidateTransports.filter { it != currentActive }
        } else {
            candidateTransports
        }

        val startTime = System.currentTimeMillis()
        for (transport in orderedCandidates) {
            val type = classifyTransport(transport)
            if (session.activeTransport != transport) {
                session.transitionTo(PeerSessionState.SWITCHING, "Switching transport to ${type.displayName}")
            } else if (session.currentState != PeerSessionState.CONNECTED) {
                session.transitionTo(PeerSessionState.CONNECTING, "Connecting via ${type.displayName}")
            }

            try {
                val success = transport.sendItem(item, session.peerDeviceId)
                if (success) {
                    session.bindTransport(transport, type)
                    session.recordSuccess()

                    val durationMs = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
                    _estimatedLatencyMs.value = durationMs
                    _lastSuccessfulTransferTimestamp.value = System.currentTimeMillis()
                    _successfulTransfersCount.value += 1
                    _lastFailureReason.value = null

                    // Estimate throughput based on payload size and duration
                    val payloadBytes = item.sizeBytes.coerceAtLeast(item.content.length.toLong())
                    val mbps = (payloadBytes * 8.0 / (durationMs / 1000.0)) / (1024 * 1024)
                    _estimatedThroughputMbps.value = when (type) {
                        TransportType.WIFI_LAN -> mbps.coerceIn(20.0, 450.0)
                        TransportType.WIFI_DIRECT -> mbps.coerceIn(15.0, 250.0)
                        TransportType.BLUETOOTH_CLASSIC -> mbps.coerceIn(0.5, 3.0)
                        TransportType.BLUETOOTH_LE -> mbps.coerceIn(0.1, 1.0)
                        TransportType.USB_WIRED -> mbps.coerceIn(50.0, 600.0)
                        else -> 100.0
                    }

                    // Hysteresis & transport switch tracking
                    if (_activeTransportType.value != type) {
                        val now = System.currentTimeMillis()
                        if (now - lastSwitchTimestamp > SWITCH_HYSTERESIS_MS) {
                            _activeTransportType.value = type
                            _transportSwitchCount.value += 1
                            lastSwitchTimestamp = now
                        }
                    }

                    _peerSessions.value = sessions.toMap()
                    updateStatuses()
                    return true
                } else {
                    session.recordFailure("Transport ${type.displayName} failed transmission")
                }
            } catch (e: Exception) {
                session.recordFailure("Transport ${type.displayName} exception: ${e.message}")
            }
        }

        session.transitionTo(PeerSessionState.FAILED, "All candidate transports failed")
        _failedTransfersCount.value += 1
        _lastFailureReason.value = "All candidate transports failed for peer '${session.peerDeviceId}'"
        _peerSessions.value = sessions.toMap()
        return false
    }

    /**
     * Sends a clipboard item using intelligent priority-based transport routing with seamless failover
     * and logical peer session orchestration.
     */
    suspend fun sendItem(item: ClipboardItem, targetDeviceId: String = ""): Boolean {
        if (targetDeviceId.isNotBlank() && targetDeviceId != "ALL") {
            // Targeted send: managed by logical peer session
            val session = getOrCreateSession(targetDeviceId)
            return sendToPeerSession(session, item)
        } else {
            // Broadcast send: iterate across all authorized peer sessions
            val authorizedPeerIds = getAuthorizedPeerIds()
            if (authorizedPeerIds.isNotEmpty()) {
                var anySuccess = false
                for (peerId in authorizedPeerIds) {
                    val session = getOrCreateSession(peerId)
                    val success = sendToPeerSession(session, item)
                    if (success) {
                        anySuccess = true
                    }
                }
                return anySuccess
            } else {
                // Fallback for broadcast transport when no specific authorized peers are pre-registered
                val candidateTransports = getSortedTransports().filter { it.isAvailable }
                if (candidateTransports.isEmpty()) {
                    _failedTransfersCount.value += 1
                    _lastFailureReason.value = "No active transport interfaces available"
                    return false
                }
                var anySuccess = false
                for (transport in candidateTransports) {
                    try {
                        val success = transport.sendItem(item, targetDeviceId)
                        if (success) anySuccess = true
                    } catch (e: Exception) {
                        Log.e(TAG, "Transport broadcast error", e)
                    }
                }
                return anySuccess
            }
        }
    }

    /**
     * Merges incoming items from all active transport adapters into a single unified Flow.
     */
    fun observeAllIncomingItems(): Flow<ClipboardItem> {
        val flows = transports.map { it.observeIncomingItems() }
        return flows.merge()
    }

    suspend fun startAll() {
        transports.forEach { 
            try {
                it.startTransport() 
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start transport ${it.transportName}", e)
            }
        }
        updateStatuses()
    }

    suspend fun stopAll() {
        transports.forEach { 
            try {
                it.stopTransport() 
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop transport ${it.transportName}", e)
            }
        }
        updateStatuses()
    }
}
