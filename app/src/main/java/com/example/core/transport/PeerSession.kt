package com.example.core.transport

import com.example.core.adapter.TransportAdapter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lifecycle states of a logical peer session.
 */
enum class PeerSessionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DEGRADED,
    SWITCHING,
    FAILED
}

/**
 * Logical representation of a session with a remote peer.
 * Keyed by the authoritative persistent [peerDeviceId], independent of the physical transport
 * currently carrying communication (Wi-Fi/TCP, Bluetooth, Wi-Fi Direct).
 *
 * Architecture:
 * Logical Peer (peerDeviceId) -> Peer Session -> Active Transport -> Transport Connection
 */
class LogicalPeerSession(
    val peerDeviceId: String,
    var peerDeviceName: String = ""
) {
    private val _state = MutableStateFlow(PeerSessionState.DISCONNECTED)
    val state: StateFlow<PeerSessionState> = _state.asStateFlow()
    val currentState: PeerSessionState get() = _state.value

    private val _activeTransportType = MutableStateFlow<TransportType?>(null)
    val activeTransportType: StateFlow<TransportType?> = _activeTransportType.asStateFlow()

    @Volatile
    var activeTransport: TransportAdapter? = null
        private set

    @Volatile
    var lastSuccessfulCommunication: Long = 0L
        private set

    @Volatile
    var lastFailureReason: String? = null
        private set

    @Volatile
    var consecutiveFailures: Int = 0
        private set

    @Volatile
    var totalTransfers: Int = 0
        private set

    @Volatile
    var successfulTransfers: Int = 0
        private set

    @Volatile
    var healthScore: Double = 1.0
        private set

    fun transitionTo(newState: PeerSessionState, reason: String? = null) {
        _state.value = newState
        if (reason != null) {
            lastFailureReason = reason
        }
    }

    fun bindTransport(transport: TransportAdapter, type: TransportType) {
        activeTransport = transport
        _activeTransportType.value = type
        _state.value = PeerSessionState.CONNECTED
        lastFailureReason = null
    }

    fun unbindTransport() {
        activeTransport = null
        _activeTransportType.value = null
        _state.value = PeerSessionState.DISCONNECTED
    }

    fun recordSuccess() {
        lastSuccessfulCommunication = System.currentTimeMillis()
        consecutiveFailures = 0
        totalTransfers++
        successfulTransfers++
        healthScore = (healthScore * 0.8 + 1.0 * 0.2).coerceIn(0.0, 1.0)
        _state.value = PeerSessionState.CONNECTED
        lastFailureReason = null
    }

    fun recordFailure(reason: String) {
        lastFailureReason = reason
        consecutiveFailures++
        totalTransfers++
        healthScore = (healthScore * 0.8 + 0.0 * 0.2).coerceIn(0.0, 1.0)
        if (consecutiveFailures >= 3) {
            _state.value = PeerSessionState.FAILED
        } else {
            _state.value = PeerSessionState.DEGRADED
        }
    }

    fun reset() {
        _state.value = PeerSessionState.DISCONNECTED
        _activeTransportType.value = null
        activeTransport = null
        consecutiveFailures = 0
        healthScore = 1.0
        lastFailureReason = null
    }
}

/**
 * Type alias for backward compatibility and clean domain naming.
 */
typealias PeerSession = LogicalPeerSession
