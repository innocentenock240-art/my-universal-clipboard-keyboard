package com.example.sync.transport

import com.example.core.adapter.TransportAdapter
import com.example.data.model.ClipboardItem
import kotlinx.coroutines.flow.Flow

/**
 * Transport interface abstraction for multi-transport sync engine.
 * Future implementations will include Wi-Fi (mDNS/Sockets), Bluetooth, and Cloud.
 * Implements the universal [TransportAdapter] contract.
 */
interface Transport : TransportAdapter {
    override val transportName: String
    override val isAvailable: Boolean

    override suspend fun startTransport() {}
    override suspend fun stopTransport() {}

    suspend fun startDiscovery()
    suspend fun stopDiscovery()
    override suspend fun sendItem(item: ClipboardItem, targetDeviceId: String): Boolean
    override fun observeIncomingItems(): Flow<ClipboardItem>
}

