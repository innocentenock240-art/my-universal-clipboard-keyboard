package com.example.core.policy

import com.example.data.model.ClipboardItem

/**
 * Authoritative Operational Sync State.
 * Resolves the real-time operational status based on network presence, user policy, and connected transport sessions.
 */
enum class OperationalSyncState {
    OFFLINE,     // Wi-Fi / Local network is unavailable
    PAUSED,      // User explicitly paused sync in policy
    LOCAL_ONLY,  // Network exists and sync may be enabled, but no authorized peers are currently connected
    SYNCING      // AutoSync enabled, network available, and actively connected to at least one authorized peer
}

fun resolveOperationalSyncState(
    isWifiAvailable: Boolean,
    syncPolicy: SyncPolicy,
    connectedAuthorizedPeerCount: Int
): OperationalSyncState {
    return when {
        syncPolicy.isSyncPaused -> OperationalSyncState.PAUSED
        !isWifiAvailable -> OperationalSyncState.OFFLINE
        !syncPolicy.isAutoSyncEnabled || connectedAuthorizedPeerCount <= 0 -> OperationalSyncState.LOCAL_ONLY
        else -> OperationalSyncState.SYNCING
    }
}

/**
 * Policy defining whether a clipboard item should be synchronized or kept local.
 */
enum class SyncScope {
    AUTO,        // Synchronize according to global settings
    SYNC_ALL,    // Explicitly broadcast to all connected peers
    SYNC_TARGET, // Explicitly send to a selected peer
    LOCAL_ONLY   // Do not synchronize, keep on current device only
}

/**
 * Destination targeting for explicit SendRequest.
 */
sealed class SendDestination {
    data class SpecificPeer(val peerDeviceId: String) : SendDestination()
    object AllTrustedPeers : SendDestination()
}

/**
 * Explicit Send Request contract representing intentional user action to transmit content.
 */
data class ExplicitSendRequest(
    val items: List<ClipboardItem>,
    val destination: SendDestination,
    val isUserAuthorized: Boolean = true,
    val requestId: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Result of an explicit send operation.
 */
sealed class SendResult {
    data class Success(val requestId: String, val deliveredCount: Int) : SendResult()
    data class Queued(val requestId: String, val queuedCount: Int, val reason: String) : SendResult()
    data class Rejected(val requestId: String, val reason: String) : SendResult()
    data class Failed(val requestId: String, val error: String) : SendResult()
}

/**
 * Type of synchronization request trigger.
 */
enum class SyncRequestType {
    AUTOMATIC,
    MANUAL,
    TARGETED,
    BROADCAST
}

/**
 * Request to synchronize a specific clipboard item.
 */
data class SyncRequest(
    val item: ClipboardItem,
    val requestedScope: SyncScope = SyncScope.AUTO,
    val targetDeviceId: String? = null,
    val requestType: SyncRequestType = SyncRequestType.AUTOMATIC
)

/**
 * Decision outcome from SyncPolicy evaluation.
 */
sealed class SyncPolicyDecision {
    abstract val isAllowed: Boolean

    object Allowed : SyncPolicyDecision() {
        override val isAllowed: Boolean = true
    }

    data class Rejected(val reason: String) : SyncPolicyDecision() {
        override val isAllowed: Boolean = false
    }
}

/**
 * Universal Synchronization Policy Configuration.
 * Governs the policy decisions across all platforms.
 */
data class SyncPolicy(
    val isAutoSyncEnabled: Boolean = true,
    val isSyncPaused: Boolean = false,
    val defaultScope: SyncScope = SyncScope.AUTO,
    val allowedDeviceIds: Set<String> = emptySet(), // Empty = allow all paired devices
    val blockedDeviceIds: Set<String> = emptySet(),
    val maxSyncSizeBytes: Long = 5 * 1024 * 1024L
) {
    fun shouldSyncItem(targetDeviceId: String? = null, itemSizeBytes: Long = 0L): Boolean {
        if (isSyncPaused) return false
        if (itemSizeBytes > maxSyncSizeBytes) return false
        if (targetDeviceId != null) {
            if (blockedDeviceIds.contains(targetDeviceId)) return false
            if (allowedDeviceIds.isNotEmpty() && !allowedDeviceIds.contains(targetDeviceId)) return false
        }
        return isAutoSyncEnabled
    }

    fun evaluateRequest(request: SyncRequest): SyncPolicyDecision {
        if (isSyncPaused) return SyncPolicyDecision.Rejected("Sync is currently paused")
        if (request.requestedScope == SyncScope.LOCAL_ONLY) return SyncPolicyDecision.Rejected("Requested scope is LOCAL_ONLY")
        val size = request.item.sizeBytes.coerceAtLeast(request.item.content.length.toLong())
        if (size > maxSyncSizeBytes) return SyncPolicyDecision.Rejected("Item size ($size bytes) exceeds limit ($maxSyncSizeBytes bytes)")
        val target = request.targetDeviceId
        if (!target.isNullOrBlank() && target != "ALL") {
            if (blockedDeviceIds.contains(target)) return SyncPolicyDecision.Rejected("Target device $target is blocked")
            if (allowedDeviceIds.isNotEmpty() && !allowedDeviceIds.contains(target)) return SyncPolicyDecision.Rejected("Target device $target is not authorized")
        }
        if (!isAutoSyncEnabled && request.requestedScope == SyncScope.AUTO) {
            return SyncPolicyDecision.Rejected("Auto-sync is disabled")
        }
        return SyncPolicyDecision.Allowed
    }
}
