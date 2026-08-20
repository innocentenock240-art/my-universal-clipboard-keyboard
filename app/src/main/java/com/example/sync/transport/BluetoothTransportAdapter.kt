package com.example.sync.transport

import com.example.core.adapter.TransportAdapter
import com.example.data.model.ClipboardItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Android Bluetooth Transport Adapter.
 * Provides secondary/fallback transport capability for small/medium payloads over Bluetooth RFCOMM / BLE control.
 */
class BluetoothTransportAdapter(
    override val transportName: String = "Bluetooth Classic / BLE"
) : Transport {

    private var _isAvailable: Boolean = false
    override val isAvailable: Boolean
        get() = false // Bluetooth transport is a placeholder pending future milestone implementation

    private val incomingFlow = MutableSharedFlow<ClipboardItem>(extraBufferCapacity = 64)

    override suspend fun startTransport() {
        // Placeholder adapter: does not report false positive availability
        _isAvailable = false
    }

    override suspend fun stopTransport() {
        _isAvailable = false
    }

    override suspend fun startDiscovery() {
        // Bluetooth device discovery pending implementation
    }

    override suspend fun stopDiscovery() {
        // Stop Bluetooth device discovery
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
