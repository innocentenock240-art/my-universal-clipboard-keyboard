package com.example.core.transport

/**
 * Universal transport medium types.
 */
enum class TransportType(val displayName: String) {
    WIFI_LAN("Wi-Fi LAN / TCP"),
    WIFI_DIRECT("Wi-Fi Direct (P2P)"),
    BLUETOOTH_LE("Bluetooth Low Energy"),
    BLUETOOTH_CLASSIC("Bluetooth Classic"),
    USB_WIRED("USB / Wired Network"),
    LOCAL_LOOPBACK("Local Loopback")
}

/**
 * Status and health metrics of a transport channel.
 */
data class TransportStatus(
    val transportType: TransportType,
    val isAvailable: Boolean,
    val isConnected: Boolean,
    val activeSessionCount: Int = 0,
    val roundTripTimeMs: Long = 0L,
    val mtuBytes: Int = 64 * 1024
)
