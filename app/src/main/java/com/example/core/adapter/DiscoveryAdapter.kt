package com.example.core.adapter

import com.example.data.model.Device
import kotlinx.coroutines.flow.StateFlow

/**
 * Universal abstraction for local network discovery.
 * Can be implemented by Android NsdManager, Windows mDNS/WS-Discovery, macOS Bonjour, Linux Avahi, or iOS LocalNetwork.
 */
interface DiscoveryAdapter {
    val isDiscovering: StateFlow<Boolean>
    val discoveredPeers: StateFlow<List<Device>>

    suspend fun startDiscovery()
    suspend fun stopDiscovery()
    suspend fun announcePresence(servicePort: Int)
    suspend fun stopAnnouncingPresence()
}
