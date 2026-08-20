package com.example.data.repository

import com.example.data.database.dao.ClipboardItemDao
import com.example.data.database.dao.PendingDeliveryDao
import com.example.data.database.entity.DeliveryState
import com.example.data.database.entity.PendingClipboardDeliveryEntity
import com.example.data.database.entity.toDomainModel
import com.example.data.database.entity.toEntity
import com.example.data.model.ClipboardItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ClipboardRepository(
    private val dao: ClipboardItemDao,
    private val pendingDao: PendingDeliveryDao? = null
) {
    val clipboardHistory: Flow<List<ClipboardItem>> = dao.observeAllItems().map { entities ->
        entities.map { it.toDomainModel() }
    }

    suspend fun getItemById(id: String): ClipboardItem? {
        return dao.getItemById(id)?.toDomainModel()
    }

    suspend fun getItemByHash(hash: String): ClipboardItem? {
        return dao.getItemByHash(hash)?.toDomainModel()
    }

    suspend fun insertClipboardItem(item: ClipboardItem) {
        val effectiveHash = if (item.hash.isNotBlank()) {
            item.hash
        } else {
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(item.content.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
        val effectiveItem = if (item.hash.isNotBlank()) item else item.copy(hash = effectiveHash)
        val existing = dao.getItemByHash(effectiveHash)
        if (existing != null) {
            // Deduplicate: Keep original item ID, favorite, and pin state, but update timestamp to newest
            val updated = existing.copy(
                createdAt = maxOf(existing.createdAt, effectiveItem.createdAt),
                expiresAt = maxOf(existing.expiresAt, effectiveItem.expiresAt),
                sourceDeviceId = if (effectiveItem.sourceDeviceId.isNotBlank() && !effectiveItem.sourceDeviceId.startsWith("dev_local")) effectiveItem.sourceDeviceId else existing.sourceDeviceId,
                sourceDeviceName = if (effectiveItem.sourceDeviceName.isNotBlank() && !effectiveItem.sourceDeviceName.startsWith("My Phone")) effectiveItem.sourceDeviceName else existing.sourceDeviceName
            )
            dao.insertItem(updated)
        } else {
            dao.insertItem(effectiveItem.toEntity())
        }
    }

    suspend fun deleteClipboardItem(id: String) {
        dao.deleteItemById(id)
        pruneOrphanDeliveries()
    }

    suspend fun deleteItemsByIds(ids: List<String>) {
        if (ids.isNotEmpty()) {
            dao.deleteItemsByIds(ids)
            pruneOrphanDeliveries()
        }
    }

    suspend fun toggleFavorite(id: String, currentFavoriteState: Boolean) {
        dao.updateFavorite(id, !currentFavoriteState)
    }

    suspend fun togglePin(id: String, currentPinState: Boolean) {
        dao.updatePin(id, !currentPinState)
    }

    suspend fun clearAll() {
        dao.clearAll()
    }

    suspend fun deleteExpiredItems(currentTime: Long = System.currentTimeMillis()): Int {
        val count = dao.deleteExpiredItems(currentTime)
        pruneOrphanDeliveries()
        return count
    }

    // Pending Deliveries Persistence Support
    suspend fun enqueuePendingDelivery(delivery: PendingClipboardDeliveryEntity) {
        pendingDao?.insertDelivery(delivery)
    }

    suspend fun enqueuePendingDeliveries(deliveries: List<PendingClipboardDeliveryEntity>) {
        if (deliveries.isNotEmpty()) {
            pendingDao?.insertDeliveries(deliveries)
        }
    }

    suspend fun getDeliveryById(deliveryId: String): PendingClipboardDeliveryEntity? {
        return pendingDao?.getDeliveryById(deliveryId)
    }

    suspend fun getPendingDeliveriesForPeer(targetPeerDeviceId: String): List<PendingClipboardDeliveryEntity> {
        return pendingDao?.getPendingDeliveriesForPeer(targetPeerDeviceId) ?: emptyList()
    }

    suspend fun getPendingDeliveriesReadyForRetry(currentTime: Long = System.currentTimeMillis()): List<PendingClipboardDeliveryEntity> {
        return pendingDao?.getPendingDeliveriesReadyForRetry(currentTime) ?: emptyList()
    }

    suspend fun getAllDeliveries(): List<PendingClipboardDeliveryEntity> {
        return pendingDao?.getAllDeliveries() ?: emptyList()
    }

    suspend fun markDeliveryAcknowledged(deliveryId: String, acknowledgedAt: Long = System.currentTimeMillis()) {
        pendingDao?.markAcknowledged(deliveryId, acknowledgedAt)
    }

    suspend fun markDeliveryFailed(deliveryId: String, reason: String) {
        pendingDao?.markFailed(deliveryId, reason)
    }

    suspend fun updateDeliveryAttempt(
        deliveryId: String,
        attemptCount: Int,
        lastAttemptAt: Long,
        nextAttemptAt: Long,
        state: String = DeliveryState.PENDING.name
    ) {
        pendingDao?.updateAttempt(deliveryId, attemptCount, lastAttemptAt, nextAttemptAt, state)
    }

    suspend fun deleteDelivery(deliveryId: String) {
        pendingDao?.deleteDelivery(deliveryId)
    }

    suspend fun pruneAcknowledgedDeliveries(cutoffTimestamp: Long): Int {
        return pendingDao?.pruneAcknowledgedDeliveries(cutoffTimestamp) ?: 0
    }

    suspend fun pruneOrphanDeliveries(): Int {
        return pendingDao?.pruneOrphanDeliveries() ?: 0
    }

    companion object {
        const val DEFAULT_RETENTION_DAYS = 7L
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

        fun calculateExpirationTime(
            createdAt: Long = System.currentTimeMillis(),
            retentionDays: Long = DEFAULT_RETENTION_DAYS
        ): Long {
            return createdAt + (retentionDays * MILLIS_PER_DAY)
        }
    }
}
