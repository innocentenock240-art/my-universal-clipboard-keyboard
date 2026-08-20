package com.example.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Lifecycle states for persistent clipboard deliveries.
 */
enum class DeliveryState {
    PENDING,
    IN_TRANSIT,
    ACKNOWLEDGED,
    FAILED
}

@Entity(
    tableName = "pending_deliveries",
    indices = [
        Index(value = ["targetPeerDeviceId", "state"]),
        Index(value = ["clipboardItemId"]),
        Index(value = ["createdAt"])
    ]
)
data class PendingClipboardDeliveryEntity(
    @PrimaryKey val deliveryId: String,
    val clipboardItemId: String,
    val targetPeerDeviceId: String,
    val state: String = DeliveryState.PENDING.name,
    val attemptCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val lastAttemptAt: Long = 0L,
    val nextAttemptAt: Long = 0L,
    val acknowledgedAt: Long? = null,
    val failureReason: String? = null
)
