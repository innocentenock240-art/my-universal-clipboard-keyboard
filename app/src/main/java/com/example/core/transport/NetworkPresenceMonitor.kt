package com.example.core.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Monitors Android network lifecycle and connectivity presence.
 * Automatically signals network restoration (Wi-Fi, Ethernet, P2P) to trigger
 * discovery recovery and re-advertisement across transports.
 */
class NetworkPresenceMonitor(
    private val context: Context
) {
    companion object {
        private const val TAG = "NetworkPresenceMonitor"
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _isWifiAvailable = MutableStateFlow(false)
    val isWifiAvailable: StateFlow<Boolean> = _isWifiAvailable.asStateFlow()

    private val _isNetworkConnected = MutableStateFlow(false)
    val isNetworkConnected: StateFlow<Boolean> = _isNetworkConnected.asStateFlow()

    var onNetworkRestored: (() -> Unit)? = null
    var onNetworkLost: (() -> Unit)? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            Log.i(TAG, "Network became available: $network")
            updateNetworkState()
            onNetworkRestored?.invoke()
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            Log.i(TAG, "Network lost: $network")
            updateNetworkState()
            onNetworkLost?.invoke()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            val hasWifiOrLan = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE) ||
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            _isWifiAvailable.value = hasWifiOrLan
            _isNetworkConnected.value = hasWifiOrLan || networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        }
    }

    fun startMonitoring() {
        try {
            updateNetworkState()
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()
            try {
                connectivityManager?.registerNetworkCallback(request, networkCallback)
            } catch (e: Exception) {
                // Fallback to default network callback if specific transport registration fails
                connectivityManager?.registerDefaultNetworkCallback(networkCallback)
            }
            Log.i(TAG, "Network presence monitoring started successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network presence callback", e)
        }
    }

    fun stopMonitoring() {
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
            Log.i(TAG, "Network presence monitoring stopped.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister network presence callback", e)
        }
    }

    fun updateNetworkState() {
        val activeNetwork = connectivityManager?.activeNetwork
        val caps = connectivityManager?.getNetworkCapabilities(activeNetwork)
        
        // LAN-only rule: Local Wi-Fi and Ethernet do NOT require NET_CAPABILITY_INTERNET
        val hasWifiOrLan = caps != null && (
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        )
        
        // A network is connected if LAN or local link is available, or general network capabilities exist
        val connected = hasWifiOrLan || (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED))

        _isNetworkConnected.value = connected
        _isWifiAvailable.value = hasWifiOrLan
    }
}
