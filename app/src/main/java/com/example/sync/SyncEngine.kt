package com.example.sync

import android.util.Log
import com.example.core.adapter.TransportAdapter
import com.example.core.identity.DeviceTrustManager
import com.example.core.policy.ExplicitSendRequest
import com.example.core.policy.SendDestination
import com.example.core.policy.SendResult
import com.example.core.policy.SyncPolicyManager
import com.example.core.policy.SyncScope
import com.example.core.transport.TransportManager
import com.example.data.database.entity.DeliveryState
import com.example.data.database.entity.PendingClipboardDeliveryEntity
import com.example.data.model.ClipboardItem
import com.example.data.repository.ClipboardRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Orchestrates multi-transport synchronization.
 * Decoupled from specific networking layers to support Wi-Fi, Bluetooth, and Cloud.
 * Integrates persistent delivery queue, exponential backoff retries, and reconnection flushing.
 */
class SyncEngine(
    val transportManager: TransportManager,
    val repository: ClipboardRepository? = null
) {
    /**
     * Secondary constructor for direct transport list initialization without repository.
     */
    constructor(transports: List<TransportAdapter>) : this(TransportManager(transports), null)

    /**
     * Secondary constructor for direct transport list initialization with repository.
     */
    constructor(transports: List<TransportAdapter>, repository: ClipboardRepository) : this(TransportManager(transports), repository)

    private val engineScope = CoroutineScope(Dispatchers.IO)

    companion object {
        private const val TAG = "SyncEngine"
        const val MAX_RETRIES = 5
        const val BASE_BACKOFF_MS = 2000L
        const val MAX_BACKOFF_MS = 60000L

        /**
         * Calculates exponential backoff in milliseconds:
         * 2000ms, 4000ms, 8000ms, 16000ms, 32000ms, capped at 60000ms.
         */
        fun calculateBackoff(attemptCount: Int): Long {
            if (attemptCount <= 0) return BASE_BACKOFF_MS
            val shift = minOf(attemptCount, 10)
            val delay = BASE_BACKOFF_MS * (1L shl shift)
            return minOf(delay, MAX_BACKOFF_MS)
        }
    }

    /**
     * Dispatches a local [ClipboardItem] to synchronized peers using intelligent multi-transport orchestration.
     * Persists pending delivery records, evaluates sync policy, and updates ACK states.
     */
    suspend fun syncClipboardItem(item: ClipboardItem, targetDeviceId: String = ""): Boolean {
        val policy = SyncPolicyManager.getPolicy()

        // 1. Resolve Target Peers
        val targetPeers = if (targetDeviceId.isNotBlank() && targetDeviceId != "ALL") {
            listOf(targetDeviceId)
        } else {
            val trustedPeers = DeviceTrustManager.getAllTrustedPeers().map { it.peerDeviceId }
            val sessionPeers = transportManager.getAllSessions().map { it.peerDeviceId }
            (trustedPeers + sessionPeers).distinct().filter { it.isNotBlank() && it != item.sourceDeviceId }
        }

        if (targetPeers.isEmpty()) {
            // Direct broadcast or unknown peers
            val deliveryId = "del_${item.id}_${if (targetDeviceId.isBlank()) "ALL" else targetDeviceId}"
            val initialDelivery = PendingClipboardDeliveryEntity(
                deliveryId = deliveryId,
                clipboardItemId = item.id,
                targetPeerDeviceId = targetDeviceId.ifBlank { "ALL" },
                createdAt = item.createdAt,
                state = DeliveryState.PENDING.name
            )
            repository?.enqueuePendingDelivery(initialDelivery)

            if (policy.isSyncPaused) {
                Log.d(TAG, "Sync is paused. Queued delivery $deliveryId as PENDING.")
                return false
            }

            val success = transportManager.sendItem(item, targetDeviceId)
            if (success) {
                repository?.markDeliveryAcknowledged(deliveryId, System.currentTimeMillis())
            } else {
                val backoff = calculateBackoff(1)
                repository?.updateDeliveryAttempt(deliveryId, 1, System.currentTimeMillis(), System.currentTimeMillis() + backoff)
            }
            return success
        }

        var allSucceeded = true

        for (peer in targetPeers) {
            val deliveryId = "del_${item.id}_$peer"
            val initialDelivery = PendingClipboardDeliveryEntity(
                deliveryId = deliveryId,
                clipboardItemId = item.id,
                targetPeerDeviceId = peer,
                createdAt = item.createdAt,
                state = DeliveryState.PENDING.name
            )
            repository?.enqueuePendingDelivery(initialDelivery)

            // Policy Gate: Blocked devices
            if (policy.blockedDeviceIds.contains(peer)) {
                Log.w(TAG, "Skipping delivery $deliveryId: peer $peer is blocked by policy.")
                repository?.markDeliveryFailed(deliveryId, "Blocked by policy")
                allSucceeded = false
                continue
            }

            // Policy Gate: Sync Paused
            if (policy.isSyncPaused) {
                Log.d(TAG, "Sync is paused. Preserved delivery $deliveryId as PENDING.")
                allSucceeded = false
                continue
            }

            // Peer Authorization Gate
            if (!transportManager.isPeerAuthorized(peer)) {
                Log.w(TAG, "Skipping delivery $deliveryId: peer $peer is not authorized.")
                repository?.markDeliveryFailed(deliveryId, "Unauthorized peer")
                allSucceeded = false
                continue
            }

            // Transmit via multi-transport layer
            val success = transportManager.sendItem(item, peer)
            if (success) {
                repository?.markDeliveryAcknowledged(deliveryId, System.currentTimeMillis())
                Log.d(TAG, "Item [${item.id}] successfully delivered and ACKed by peer $peer.")
            } else {
                allSucceeded = false
                val backoff = calculateBackoff(1)
                repository?.updateDeliveryAttempt(
                    deliveryId = deliveryId,
                    attemptCount = 1,
                    lastAttemptAt = System.currentTimeMillis(),
                    nextAttemptAt = System.currentTimeMillis() + backoff,
                    state = DeliveryState.PENDING.name
                )
                Log.w(TAG, "Delivery failed for peer $peer. Scheduled retry with ${backoff}ms backoff.")
            }
        }

        return allSucceeded
    }

    /**
     * Authoritative Synchronization Gate for explicit user send requests.
     * Evaluates operational sync policy, peer trust, destination validity, and executes transmission.
     */
    suspend fun executeExplicitSendRequest(
        request: ExplicitSendRequest,
        onProgress: ((bytesIncrement: Long) -> Unit)? = null
    ): SendResult {
        val policy = SyncPolicyManager.getPolicy()

        // 1. Authoritative PAUSE Gate: No outbound transmission when paused
        if (policy.isSyncPaused) {
            Log.w(TAG, "ExplicitSendRequest ${request.requestId} rejected: Synchronization is currently PAUSED.")
            return SendResult.Rejected(request.requestId, "Synchronization is currently paused.")
        }

        // 2. User Authorization Gate
        if (!request.isUserAuthorized) {
            Log.w(TAG, "ExplicitSendRequest ${request.requestId} rejected: Request was not authorized by user.")
            return SendResult.Rejected(request.requestId, "Send request was not authorized by user.")
        }

        // 3. Payload Validation Gate
        if (request.items.isEmpty()) {
            Log.w(TAG, "ExplicitSendRequest ${request.requestId} rejected: No items selected to send.")
            return SendResult.Rejected(request.requestId, "No items selected to send.")
        }

        // 4. Destination Resolution
        val targetPeerIds = when (val dest = request.destination) {
            is SendDestination.SpecificPeer -> {
                val peerId = dest.peerDeviceId.trim()
                if (peerId.isBlank()) {
                    return SendResult.Rejected(request.requestId, "Target peer ID cannot be empty.")
                }
                if (policy.blockedDeviceIds.contains(peerId)) {
                    return SendResult.Rejected(request.requestId, "Target peer $peerId is blocked by policy.")
                }
                if (!transportManager.isPeerAuthorized(peerId) && !DeviceTrustManager.isPeerTrusted(peerId)) {
                    return SendResult.Rejected(request.requestId, "Target peer $peerId is not a trusted or authorized peer.")
                }
                listOf(peerId)
            }
            is SendDestination.AllTrustedPeers -> {
                val trusted = DeviceTrustManager.getAllTrustedPeers()
                    .map { it.peerDeviceId }
                    .filter { it.isNotBlank() && !policy.blockedDeviceIds.contains(it) }
                val sessionPeers = transportManager.getAllSessions()
                    .map { it.peerDeviceId }
                    .filter { it.isNotBlank() && !policy.blockedDeviceIds.contains(it) && (transportManager.isPeerAuthorized(it) || DeviceTrustManager.isPeerTrusted(it)) }
                (trusted + sessionPeers).distinct()
            }
        }

        if (targetPeerIds.isEmpty()) {
            Log.w(TAG, "ExplicitSendRequest ${request.requestId} rejected: No eligible trusted peers found.")
            return SendResult.Rejected(request.requestId, "No eligible trusted peers found.")
        }

        var deliveredCount = 0
        var queuedCount = 0
        var failedCount = 0

        for (item in request.items) {
            // Size limit check
            val itemSize = item.sizeBytes.coerceAtLeast(item.content.length.toLong())
            if (itemSize > policy.maxSyncSizeBytes) {
                Log.w(TAG, "Skipping item ${item.id}: size ($itemSize bytes) exceeds max limit (${policy.maxSyncSizeBytes} bytes).")
                failedCount++
                continue
            }

            for (peerId in targetPeerIds) {
                if (peerId == item.sourceDeviceId) {
                    continue // Do not echo back to sender
                }

                val deliveryId = "del_${item.id}_$peerId"
                val pendingDelivery = PendingClipboardDeliveryEntity(
                    deliveryId = deliveryId,
                    clipboardItemId = item.id,
                    targetPeerDeviceId = peerId,
                    createdAt = item.createdAt,
                    state = DeliveryState.PENDING.name
                )
                repository?.enqueuePendingDelivery(pendingDelivery)

                val success = transportManager.sendItem(item, peerId)
                if (success) {
                    repository?.markDeliveryAcknowledged(deliveryId, System.currentTimeMillis())
                    deliveredCount++
                    onProgress?.invoke(itemSize)
                    Log.i(TAG, "Item [${item.id}] successfully delivered to peer $peerId.")
                } else {
                    queuedCount++
                    val backoff = calculateBackoff(1)
                    repository?.updateDeliveryAttempt(
                        deliveryId = deliveryId,
                        attemptCount = 1,
                        lastAttemptAt = System.currentTimeMillis(),
                        nextAttemptAt = System.currentTimeMillis() + backoff,
                        state = DeliveryState.PENDING.name
                    )
                    Log.w(TAG, "Delivery to peer $peerId failed on immediate attempt. Preserved in pending queue.")
                }
            }
        }

        return when {
            deliveredCount > 0 -> {
                SendResult.Success(request.requestId, deliveredCount)
            }
            queuedCount > 0 -> {
                SendResult.Queued(request.requestId, queuedCount, "Saved to pending queue for delivery when peer connects.")
            }
            else -> {
                SendResult.Failed(request.requestId, "Failed to deliver payload to target peer(s).")
            }
        }
    }

    /**
     * Executes a complete synchronization request evaluating against the given [SyncPolicy].
     * Enforces policy decisions, scope routing (AUTO, SYNC_ALL, SYNC_TARGET, LOCAL_ONLY),
     * and dispatches across the transport management layer.
     */
    suspend fun executeSyncRequest(
        request: com.example.core.policy.SyncRequest,
        policy: com.example.core.policy.SyncPolicy
    ): Boolean {
        val decision = policy.evaluateRequest(request)
        if (!decision.isAllowed) {
            val reason = (decision as? com.example.core.policy.SyncPolicyDecision.Rejected)?.reason ?: "Rejected by policy"
            Log.d(TAG, "SyncRequest for item ${request.item.id} rejected: $reason")
            return false
        }

        val targetDeviceId = when (request.requestedScope) {
            SyncScope.SYNC_TARGET -> request.targetDeviceId ?: ""
            SyncScope.SYNC_ALL, SyncScope.AUTO -> "ALL"
            SyncScope.LOCAL_ONLY -> return false
        }

        return syncClipboardItem(request.item, targetDeviceId)
    }

    /**
     * Periodically processes eligible pending deliveries with exponential backoff.
     */
    suspend fun processPendingDeliveries() {
        val repo = repository ?: return
        val policy = SyncPolicyManager.getPolicy()

        if (policy.isSyncPaused) {
            Log.d(TAG, "Skipping pending delivery processing: sync is paused.")
            return
        }

        val readyDeliveries = repo.getPendingDeliveriesReadyForRetry(System.currentTimeMillis())
        for (delivery in readyDeliveries) {
            // Check orphan
            val item = repo.getItemById(delivery.clipboardItemId)
            if (item == null) {
                Log.w(TAG, "Pruning orphan delivery ${delivery.deliveryId} (item not found in repository).")
                repo.deleteDelivery(delivery.deliveryId)
                continue
            }

            // Check blocked peer
            if (policy.blockedDeviceIds.contains(delivery.targetPeerDeviceId)) {
                Log.w(TAG, "Marking delivery ${delivery.deliveryId} as FAILED: peer is blocked.")
                repo.markDeliveryFailed(delivery.deliveryId, "Blocked by policy")
                continue
            }

            // Check peer authorization
            if (!transportManager.isPeerAuthorized(delivery.targetPeerDeviceId)) {
                Log.w(TAG, "Marking delivery ${delivery.deliveryId} as FAILED: peer is unauthorized.")
                repo.markDeliveryFailed(delivery.deliveryId, "Unauthorized peer")
                continue
            }

            // Check max retries
            val newAttemptCount = delivery.attemptCount + 1
            if (newAttemptCount >= MAX_RETRIES) {
                Log.w(TAG, "Delivery ${delivery.deliveryId} reached max retry threshold ($newAttemptCount >= $MAX_RETRIES). Transitioning to FAILED.")
                repo.updateDeliveryAttempt(
                    deliveryId = delivery.deliveryId,
                    attemptCount = newAttemptCount,
                    lastAttemptAt = System.currentTimeMillis(),
                    nextAttemptAt = 0L,
                    state = DeliveryState.FAILED.name
                )
                continue
            }

            // Attempt delivery
            val success = transportManager.sendItem(item, delivery.targetPeerDeviceId)
            if (success) {
                repo.markDeliveryAcknowledged(delivery.deliveryId, System.currentTimeMillis())
                Log.d(TAG, "Pending delivery ${delivery.deliveryId} successfully ACKed.")
            } else {
                val backoff = calculateBackoff(newAttemptCount)
                repo.updateDeliveryAttempt(
                    deliveryId = delivery.deliveryId,
                    attemptCount = newAttemptCount,
                    lastAttemptAt = System.currentTimeMillis(),
                    nextAttemptAt = System.currentTimeMillis() + backoff,
                    state = DeliveryState.PENDING.name
                )
                Log.w(TAG, "Pending delivery ${delivery.deliveryId} failed attempt $newAttemptCount. Next retry in ${backoff}ms.")
            }
        }
    }

    /**
     * Handles peer reconnection event: flushes all queued pending deliveries for this peer in chronological order.
     */
    fun onPeerConnected(peerDeviceId: String) {
        engineScope.launch {
            val repo = repository ?: return@launch
            val policy = SyncPolicyManager.getPolicy()

            if (policy.isSyncPaused) {
                Log.d(TAG, "Peer $peerDeviceId reconnected, but sync is paused. Keeping deliveries pending.")
                return@launch
            }

            val pendingList = repo.getPendingDeliveriesForPeer(peerDeviceId)
            Log.d(TAG, "Flushing ${pendingList.size} pending deliveries for reconnected peer $peerDeviceId.")

            for (delivery in pendingList) {
                val item = repo.getItemById(delivery.clipboardItemId)
                if (item == null) {
                    repo.deleteDelivery(delivery.deliveryId)
                    continue
                }

                if (policy.blockedDeviceIds.contains(peerDeviceId)) {
                    repo.markDeliveryFailed(delivery.deliveryId, "Blocked by policy")
                    continue
                }

                if (!transportManager.isPeerAuthorized(peerDeviceId)) {
                    repo.markDeliveryFailed(delivery.deliveryId, "Unauthorized peer")
                    continue
                }

                val success = transportManager.sendItem(item, peerDeviceId)
                if (success) {
                    repo.markDeliveryAcknowledged(delivery.deliveryId, System.currentTimeMillis())
                    Log.d(TAG, "Reconnected delivery ${delivery.deliveryId} successfully delivered.")
                } else {
                    val attempt = delivery.attemptCount + 1
                    if (attempt >= MAX_RETRIES) {
                        repo.markDeliveryFailed(delivery.deliveryId, "Max retries exceeded")
                    } else {
                        val backoff = calculateBackoff(attempt)
                        repo.updateDeliveryAttempt(
                            deliveryId = delivery.deliveryId,
                            attemptCount = attempt,
                            lastAttemptAt = System.currentTimeMillis(),
                            nextAttemptAt = System.currentTimeMillis() + backoff,
                            state = DeliveryState.PENDING.name
                        )
                    }
                }
            }
        }
    }

    /**
     * Called when synchronization policy changes (e.g. unpaused or auto-sync enabled).
     */
    fun onSyncPolicyChanged() {
        engineScope.launch {
            val policy = SyncPolicyManager.getPolicy()
            if (!policy.isSyncPaused && policy.isAutoSyncEnabled) {
                processPendingDeliveries()
            }
        }
    }

    /**
     * Merges incoming item streams from all active transports into a unified flow.
     */
    fun observeIncomingItems(): Flow<ClipboardItem> {
        return transportManager.observeAllIncomingItems()
    }

    /**
     * Retrieves an active or cached logical peer session by peer device ID.
     */
    fun getPeerSession(peerDeviceId: String): com.example.core.transport.LogicalPeerSession? {
        return transportManager.getSession(peerDeviceId)
    }

    /**
     * Obtains or creates a logical peer session for a given peer device ID.
     */
    fun getOrCreatePeerSession(peerDeviceId: String, name: String = ""): com.example.core.transport.LogicalPeerSession {
        return transportManager.getOrCreateSession(peerDeviceId, name)
    }

    /**
     * Returns all logical peer sessions currently tracked by the transport orchestration layer.
     */
    fun getAllPeerSessions(): List<com.example.core.transport.LogicalPeerSession> {
        return transportManager.getAllSessions()
    }
}
