package com.example.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.database.entity.PendingClipboardDeliveryEntity

@Dao
interface PendingDeliveryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDelivery(delivery: PendingClipboardDeliveryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeliveries(deliveries: List<PendingClipboardDeliveryEntity>)

    @Query("SELECT * FROM pending_deliveries WHERE deliveryId = :deliveryId LIMIT 1")
    suspend fun getDeliveryById(deliveryId: String): PendingClipboardDeliveryEntity?

    @Query("SELECT * FROM pending_deliveries WHERE targetPeerDeviceId = :targetPeerDeviceId AND state = 'PENDING' ORDER BY createdAt ASC")
    suspend fun getPendingDeliveriesForPeer(targetPeerDeviceId: String): List<PendingClipboardDeliveryEntity>

    @Query("SELECT * FROM pending_deliveries WHERE state = 'PENDING' AND nextAttemptAt <= :currentTime ORDER BY createdAt ASC")
    suspend fun getPendingDeliveriesReadyForRetry(currentTime: Long): List<PendingClipboardDeliveryEntity>

    @Query("SELECT * FROM pending_deliveries ORDER BY createdAt ASC")
    suspend fun getAllDeliveries(): List<PendingClipboardDeliveryEntity>

    @Query("UPDATE pending_deliveries SET state = 'ACKNOWLEDGED', acknowledgedAt = :acknowledgedAt WHERE deliveryId = :deliveryId")
    suspend fun markAcknowledged(deliveryId: String, acknowledgedAt: Long)

    @Query("UPDATE pending_deliveries SET state = 'FAILED', failureReason = :reason WHERE deliveryId = :deliveryId")
    suspend fun markFailed(deliveryId: String, reason: String)

    @Query("UPDATE pending_deliveries SET attemptCount = :attemptCount, lastAttemptAt = :lastAttemptAt, nextAttemptAt = :nextAttemptAt, state = :state WHERE deliveryId = :deliveryId")
    suspend fun updateAttempt(deliveryId: String, attemptCount: Int, lastAttemptAt: Long, nextAttemptAt: Long, state: String = "PENDING")

    @Query("DELETE FROM pending_deliveries WHERE deliveryId = :deliveryId")
    suspend fun deleteDelivery(deliveryId: String)

    @Query("DELETE FROM pending_deliveries WHERE state = 'ACKNOWLEDGED' AND acknowledgedAt IS NOT NULL AND acknowledgedAt < :cutoffTimestamp")
    suspend fun pruneAcknowledgedDeliveries(cutoffTimestamp: Long): Int

    @Query("DELETE FROM pending_deliveries WHERE clipboardItemId NOT IN (SELECT id FROM clipboard_items)")
    suspend fun pruneOrphanDeliveries(): Int
}
