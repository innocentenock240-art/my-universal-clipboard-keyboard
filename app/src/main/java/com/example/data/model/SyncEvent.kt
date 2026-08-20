package com.example.data.model

/**
 * Data class tracking synchronization events across devices.
 */
data class SyncEvent(
    val eventId: String,
    val itemId: String,
    val sourceDeviceId: String,
    val destinationDeviceId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = "PENDING" // PENDING, SENT, RECEIVED, FAILED
)
