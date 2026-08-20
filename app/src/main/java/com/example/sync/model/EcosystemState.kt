package com.example.sync.model

import com.example.core.capability.DeviceCapabilities
import com.example.core.capability.PlatformType
import com.example.core.policy.OperationalSyncState
import com.example.core.policy.SyncPolicy
import com.example.data.model.ClipboardItem
import com.example.data.model.ConnectionState
import com.example.data.model.Device

/**
 * Status lifecycle of a real clipboard item transfer operation.
 * Conforms to the non-negotiable rule that transfer states must originate from real runtime execution.
 */
enum class TransferStatus {
    QUEUED,
    PREPARING,
    STREAMING,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Authoritative record of an active or recent transfer.
 * Byte counts and progress fraction are derived from real I/O operations.
 */
data class ActiveTransfer(
    val transferId: String,
    val items: List<ClipboardItem>,
    val targetPeerId: String,
    val targetPeerName: String,
    val status: TransferStatus,
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = 0L,
    val errorMessage: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    val progressFraction: Float
        get() = if (totalBytes > 0) (bytesTransferred.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

/**
 * Authoritative Ecosystem Runtime State.
 * The single source of truth for all UI layers (MainActivity, MainViewModel, IME, Panels).
 * No UI layer may invent or independently own synchronization or peer connection truth.
 */
data class EcosystemState(
    val localDevice: Device,
    val isWifiAvailable: Boolean,
    val operationalSyncState: OperationalSyncState,
    val syncPolicy: SyncPolicy,
    val trustedPeers: List<Device> = emptyList(),
    val discoveredPeers: List<Device> = emptyList(),
    val activeTransfers: List<ActiveTransfer> = emptyList()
) {
    /**
     * Number of remote peers with an active, authenticated connection.
     */
    val connectedPeerCount: Int
        get() = (trustedPeers + discoveredPeers).distinctBy { it.deviceId }
            .count { !it.isLocalDevice && it.connectionState == ConnectionState.CONNECTED }

    /**
     * Whether synchronization is currently paused by policy or operational status.
     */
    val isPaused: Boolean
        get() = syncPolicy.isSyncPaused || operationalSyncState == OperationalSyncState.PAUSED

    /**
     * Merged view of all known devices (local device + active discovered peers + remembered trusted peers).
     */
    val allDevices: List<Device>
        get() {
            val authoritativeLocalId = com.example.core.identity.DeviceIdentityManager.getLocalDeviceId()
            val authoritativeLocalName = com.example.core.identity.DeviceIdentityManager.getLocalDeviceName()
            val effectiveLocalDevice = if (localDevice.deviceId != authoritativeLocalId) {
                localDevice.copy(deviceId = authoritativeLocalId, deviceName = authoritativeLocalName)
            } else {
                localDevice
            }
            val list = mutableListOf(effectiveLocalDevice)

            // Start with trusted peers already known to the state
            val trustedMap = trustedPeers.associateBy { it.deviceId }.toMutableMap()

            // Merge any authoritative trusted peers from DeviceTrustManager
            val allTrustedRecords = com.example.core.identity.DeviceTrustManager.getAllTrustedPeers()
            for (record in allTrustedRecords) {
                if (!trustedMap.containsKey(record.peerDeviceId) && record.peerDeviceId != authoritativeLocalId) {
                    trustedMap[record.peerDeviceId] = Device(
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
            }

            val nonLocalDiscovered = discoveredPeers.filter { !it.isLocalDevice && it.deviceId != authoritativeLocalId }
            
            // 1. Add all trusted peers (which already have live connection status merged from discovery in SyncRuntime)
            list.addAll(trustedMap.values.filter { !it.isLocalDevice && it.deviceId != authoritativeLocalId })

            // 2. Add any newly discovered un-paired peers
            for (discovered in nonLocalDiscovered) {
                if (!trustedMap.containsKey(discovered.deviceId)) {
                    list.add(discovered)
                }
            }
            return list.distinctBy { it.deviceId }
        }
}
