package com.example.core.adapter

import com.example.data.model.ClipboardItem
import kotlinx.coroutines.flow.Flow

/**
 * Universal abstraction for peer-to-peer data transport.
 * Allows multi-transport routing across Wi-Fi sockets, Bluetooth, or future channels.
 */
interface TransportAdapter {
    val transportName: String
    val isAvailable: Boolean

    suspend fun startTransport()
    suspend fun stopTransport()
    suspend fun sendItem(item: ClipboardItem, targetDeviceId: String = ""): Boolean
    fun observeIncomingItems(): Flow<ClipboardItem>
}
