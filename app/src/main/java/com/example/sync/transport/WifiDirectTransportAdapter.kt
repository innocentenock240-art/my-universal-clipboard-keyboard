package com.example.sync.transport

import com.example.core.adapter.TransportAdapter
import com.example.data.model.ClipboardItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Android Wi-Fi Direct (P2P) Transport Adapter.
 * Supports peer-to-peer off-grid direct socket synchronization.
 */
class WifiDirectTransportAdapter(
    override val transportName: String = "Wi-Fi Direct (P2P)"
) : Transport {

    private var _isAvailable: Boolean = false
    override val isAvailable: Boolean
        get() = false // Wi-Fi Direct transport is a placeholder pending future milestone implementation

    private val incomingFlow = MutableSharedFlow<ClipboardItem>(extraBufferCapacity = 64)

    override suspend fun startTransport() {
        // Placeholder adapter: does not report false positive availability
        _isAvailable = false
    }

    override suspend fun stopTransport() {
        _isAvailable = false
    }

    override suspend fun startDiscovery() {
        // Wi-Fi Direct discovery pending implementation
    }

    override suspend fun stopDiscovery() {
        // Stop Wi-Fi Direct discovery
    }

    override suspend fun sendItem(item: ClipboardItem, targetDeviceId: String): Boolean {
        // Placeholder adapter: explicitly returns false to prevent false-positive session success
        return false
    }

    override fun observeIncomingItems(): Flow<ClipboardItem> = incomingFlow.asSharedFlow()

    suspend fun emitTestItem(item: ClipboardItem) {
        incomingFlow.emit(item)
    }
}
