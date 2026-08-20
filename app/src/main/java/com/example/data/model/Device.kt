package com.example.data.model

import com.example.core.capability.DeviceCapabilities
import com.example.core.capability.PlatformType

enum class ConnectionState {
    DISCOVERED,
    CONNECTING,
    RECONNECTING,
    CONNECTED,
    DISCONNECTED,
    ERROR
}

/**
 * Data class representing an authorized or discovered device in the network.
 * Supports cross-platform metadata and capability tracking.
 */
data class Device(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String = "PHONE", // PHONE, TABLET, LAPTOP, DESKTOP
    val ipAddress: String? = null,
    val publicKey: String? = null,
    val lastSeen: Long = System.currentTimeMillis(),
    val pairedAt: Long? = null,
    val isLocalDevice: Boolean = false,
    val isOnline: Boolean = false,
    val isPaired: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val platform: PlatformType = PlatformType.ANDROID,
    val capabilities: DeviceCapabilities = DeviceCapabilities.ANDROID_DEFAULT
)

