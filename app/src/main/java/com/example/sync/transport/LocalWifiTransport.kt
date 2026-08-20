package com.example.sync.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.example.core.identity.DeviceIdentityManager
import com.example.core.identity.DeviceTrustManager
import com.example.core.identity.TrustedPeerRecord
import com.example.core.policy.SyncPolicyManager
import com.example.core.protocol.ProtocolEnvelope
import com.example.core.protocol.ProtocolMessageType
import com.example.data.clipboard.ClipboardCoreManager
import com.example.data.model.ClipboardItem
import com.example.data.model.ConnectionState
import com.example.data.model.Device
import com.example.sync.model.parseClipboardItemFromJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.UUID

/**
 * Local Wi-Fi transport implementation for direct local network TCP socket communication
 * and NsdManager mDNS device discovery.
 * Responsible strictly for network transport and socket connections without clipboard capture logic.
 */
class LocalWifiTransport(
    private val context: Context? = null,
    val port: Int = DEFAULT_PORT,
    private val customDeviceId: String? = null,
    private val customDeviceName: String? = null
) : Transport {

    companion object {
        const val DEFAULT_PORT = 53711
        // Android NsdManager serviceType must NOT have a trailing dot
        private const val SERVICE_TYPE = "_uclip._tcp"
        private const val TAG = "LocalWifiTransport"
        private const val PREFS_NAME = "uclip_device_prefs"
        private const val KEY_KNOWN_PEERS = "known_peer_device_ids"

        const val CONNECT_TIMEOUT_MS = 5000
        const val HANDSHAKE_TIMEOUT_MS = 5000
        const val SOCKET_TIMEOUT_MS = 5000
        const val MAX_RECONNECT_ATTEMPTS = 3

        @Volatile
        var activeTransportInstance: LocalWifiTransport? = null
    }

    override val transportName: String = "LocalWi-Fi"

    private var serverSocket: ServerSocket? = null
    private var listeningJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _incomingItems = MutableSharedFlow<ClipboardItem>(replay = 1, extraBufferCapacity = 64)
    private val _incomingMessages = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 64)

    val incomingMessages: Flow<String> = _incomingMessages.asSharedFlow()

    private val _discoveredDevices = MutableStateFlow<List<Device>>(emptyList())
    val discoveredDevices: StateFlow<List<Device>> = _discoveredDevices.asStateFlow()

    private val knownPeerDeviceIds = java.util.Collections.synchronizedSet(HashSet<String>())
    private val reconnectingJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val inFlightAcks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<String>>()

    init {
        activeTransportInstance = this
        DeviceTrustManager.init(context)
        loadKnownPeersFromPrefs()
    }

    private fun loadKnownPeersFromPrefs() {
        val trustedIds = DeviceTrustManager.getTrustedPeerIds()
        knownPeerDeviceIds.addAll(trustedIds)
        context?.let { ctx ->
            try {
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val stored = prefs.getStringSet(KEY_KNOWN_PEERS, null)
                if (stored != null) {
                    knownPeerDeviceIds.addAll(stored)
                }
                Log.i(TAG, "Loaded ${knownPeerDeviceIds.size} known peer(s) from persistent storage: $knownPeerDeviceIds")
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to load known peers from SharedPreferences", e)
            }
        }
    }

    private fun saveKnownPeersToPrefs() {
        context?.let { ctx ->
            try {
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                synchronized(knownPeerDeviceIds) {
                    prefs.edit().putStringSet(KEY_KNOWN_PEERS, HashSet(knownPeerDeviceIds)).apply()
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to save known peers to SharedPreferences", e)
            }
        }
    }

    fun addKnownPeer(deviceId: String, deviceName: String? = null, ipHint: String? = null) {
        if (deviceId.isBlank() || deviceId == localDeviceId) return
        val added = knownPeerDeviceIds.add(deviceId)
        DeviceTrustManager.recordPeerTrust(
            context = context,
            peerDeviceId = deviceId,
            deviceName = deviceName ?: "Remote Device",
            ipHint = ipHint
        )
        if (added) {
            saveKnownPeersToPrefs()
            Log.i(TAG, "Added known peer: $deviceId. Total known peers: ${knownPeerDeviceIds.size}")
        }
    }

    fun removeKnownPeer(deviceId: String) {
        val removed = knownPeerDeviceIds.remove(deviceId)
        DeviceTrustManager.revokePeerTrust(context, deviceId)
        if (removed) {
            saveKnownPeersToPrefs()
            Log.i(TAG, "Removed known peer: $deviceId")
        }
    }

    fun authorizePeer(deviceId: String, deviceName: String? = null) {
        if (deviceId.isBlank() || deviceId == localDeviceId) return
        addKnownPeer(deviceId, deviceName)
        updateDevicePairedStatus(deviceId, true)
    }

    fun revokePeerAuthorization(deviceId: String) {
        if (deviceId.isBlank()) return
        removeKnownPeer(deviceId)
        updateDevicePairedStatus(deviceId, false)
    }

    fun isPeerAuthorized(deviceId: String): Boolean {
        return isKnownPeer(deviceId)
    }

    private fun updateDevicePairedStatus(deviceId: String, isPaired: Boolean) {
        val current = _discoveredDevices.value.toMutableList()
        val index = current.indexOfFirst { it.deviceId == deviceId }
        if (index >= 0) {
            current[index] = current[index].copy(isPaired = isPaired)
            _discoveredDevices.value = current
        }
    }

    fun isKnownPeer(deviceId: String): Boolean {
        return DeviceTrustManager.isPeerTrusted(deviceId) || knownPeerDeviceIds.contains(deviceId)
    }

    fun getKnownPeers(): Set<String> {
        return synchronized(knownPeerDeviceIds) {
            val combined = HashSet(knownPeerDeviceIds)
            combined.addAll(DeviceTrustManager.getTrustedPeerIds())
            combined
        }
    }

    fun clearKnownPeers() {
        knownPeerDeviceIds.clear()
        DeviceTrustManager.clearAllTrustedPeers(context)
        saveKnownPeersToPrefs()
        Log.i(TAG, "Cleared all known peers from storage")
    }

    private class PeerSession(
        val deviceId: String,
        val deviceName: String,
        val socket: Socket,
        val reader: BufferedReader,
        val writer: PrintWriter
    ) {
        fun closeSilently() {
            try { reader.close() } catch (_: Throwable) {}
            try { writer.close() } catch (_: Throwable) {}
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    private val activeSessions = java.util.concurrent.ConcurrentHashMap<String, PeerSession>()

    private val recentlyProcessedHashes = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    @Volatile
    private var _isListening = false

    @Volatile
    private var isNsdAdvertising = false

    @Volatile
    private var isNsdDiscovering = false

    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null
    private var nsdDiscoveryListener: NsdManager.DiscoveryListener? = null

    private var multicastLock: WifiManager.MulticastLock? = null

    private val nsdManager: NsdManager? by lazy {
        context?.applicationContext?.getSystemService(Context.NSD_SERVICE) as? NsdManager
    }

    private val wifiManager: WifiManager? by lazy {
        context?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }

    val localDeviceId: String by lazy {
        DeviceIdentityManager.getLocalDeviceId(context, customDeviceId)
    }

    val localDeviceName: String by lazy {
        customDeviceName ?: DeviceIdentityManager.getLocalDeviceName(context)
    }

    // Resolution Queue to prevent NsdManager.FAILURE_ALREADY_ACTIVE (Error 3)
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var isResolving = false

    override val isAvailable: Boolean
        get() = _isListening && serverSocket != null && !serverSocket!!.isClosed

    override suspend fun startDiscovery() {
        startServer()
        startNsdDiscovery()
    }

    override suspend fun stopDiscovery() {
        stopNsdDiscovery()
        stopNsdAdvertisement()
    }

    fun startServer() {
        if (_isListening) return
        try {
            serverSocket = ServerSocket(port).apply {
                reuseAddress = true
            }
            _isListening = true
            Log.i(TAG, "LocalWifiTransport server started on port $port")

            listeningJob = scope.launch {
                while (isActive && _isListening) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        launch { handleIncomingSocket(clientSocket) }
                    } catch (e: SocketException) {
                        Log.d(TAG, "Server socket closed or accept interrupted: ${e.message}")
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Error accepting client connection", e)
                    }
                }
            }

            // Also advertise via mDNS if context is available
            startNsdAdvertisement()
        } catch (e: java.net.BindException) {
            try {
                serverSocket = ServerSocket(0).apply {
                    reuseAddress = true
                }
                _isListening = true
                Log.i(TAG, "LocalWifiTransport fallback server started on port ${serverSocket?.localPort}")

                listeningJob = scope.launch {
                    while (isActive && _isListening) {
                        try {
                            val clientSocket = serverSocket?.accept() ?: break
                            launch { handleIncomingSocket(clientSocket) }
                        } catch (e: SocketException) {
                            Log.d(TAG, "Server socket closed or accept interrupted: ${e.message}")
                            break
                        } catch (e: Exception) {
                            Log.e(TAG, "Error accepting client connection", e)
                        }
                    }
                }
                startNsdAdvertisement()
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to start LocalWifiTransport server on fallback port", ex)
                _isListening = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start LocalWifiTransport server on port $port", e)
            _isListening = false
        }
    }

    fun stopServer() {
        _isListening = false
        listeningJob?.cancel()
        listeningJob = null
        reconnectingJobs.values.forEach { it.cancel() }
        reconnectingJobs.clear()
        stopNsdAdvertisement()
        stopNsdDiscovery()
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket", e)
        } finally {
            serverSocket = null
        }
        Log.i(TAG, "LocalWifiTransport server stopped")
    }

    fun startNsdAdvertisement() {
        if (isNsdAdvertising || nsdRegistrationListener != null || nsdManager == null) return
        acquireMulticastLock()
        try {
            val listeningPort = serverSocket?.localPort ?: this@LocalWifiTransport.port
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "UClip_$localDeviceId"
                serviceType = SERVICE_TYPE
                setPort(listeningPort)
                try {
                    setAttribute("deviceId", localDeviceId)
                    setAttribute("deviceName", localDeviceName)
                } catch (e: Throwable) {
                    Log.w(TAG, "NsdServiceInfo setAttribute unavailable", e)
                }
            }

            Log.i(TAG, "NSD Registration started for service UClip_$localDeviceId with type $SERVICE_TYPE on port $listeningPort")

            nsdRegistrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(registeredServiceInfo: NsdServiceInfo) {
                    isNsdAdvertising = true
                    Log.i(TAG, "NSD Service registered successfully: ${registeredServiceInfo.serviceName}")
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    isNsdAdvertising = false
                    nsdRegistrationListener = null
                    Log.e(TAG, "NSD Service registration failed with errorCode: $errorCode")
                    releaseMulticastLock()
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                    isNsdAdvertising = false
                    nsdRegistrationListener = null
                    Log.i(TAG, "NSD Service unregistered: ${serviceInfo.serviceName}")
                    releaseMulticastLock()
                }

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    isNsdAdvertising = false
                    nsdRegistrationListener = null
                    Log.e(TAG, "NSD Service unregistration failed with errorCode: $errorCode")
                    releaseMulticastLock()
                }
            }

            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, nsdRegistrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register NSD advertisement", e)
            nsdRegistrationListener = null
            releaseMulticastLock()
        }
    }

    fun stopNsdAdvertisement() {
        if (!isNsdAdvertising && nsdRegistrationListener == null) {
            releaseMulticastLock()
            return
        }
        try {
            nsdRegistrationListener?.let { listener ->
                nsdManager?.unregisterService(listener)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering NSD service", e)
        } finally {
            nsdRegistrationListener = null
            isNsdAdvertising = false
            releaseMulticastLock()
        }
    }

    fun startNsdDiscovery() {
        if (isNsdDiscovering || nsdManager == null) return
        acquireMulticastLock()
        try {
            nsdDiscoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {
                    isNsdDiscovering = true
                    Log.i(TAG, "NSD Discovery started for $regType")
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    Log.i(TAG, "NSD Service found: ${serviceInfo.serviceName}, type: ${serviceInfo.serviceType}")
                    if (serviceInfo.serviceType.contains("_uclip")) {
                        if (serviceInfo.serviceName.contains(localDeviceId) || serviceInfo.serviceName == "UClip_$localDeviceId") {
                            Log.d(TAG, "Ignoring self-discovered device service: ${serviceInfo.serviceName} (localDeviceId: $localDeviceId)")
                            return
                        }
                        queueResolveService(serviceInfo)
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    Log.i(TAG, "NSD Service lost: ${serviceInfo.serviceName}")
                    removeDiscoveredDeviceByServiceName(serviceInfo.serviceName)
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    isNsdDiscovering = false
                    Log.i(TAG, "NSD Discovery stopped for $serviceType")
                    releaseMulticastLock()
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    isNsdDiscovering = false
                    Log.e(TAG, "NSD Discovery start failed with errorCode: $errorCode")
                    releaseMulticastLock()
                    try {
                        nsdManager?.stopServiceDiscovery(this)
                    } catch (e: Exception) { /* ignore */ }
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    isNsdDiscovering = false
                    Log.e(TAG, "NSD Discovery stop failed with errorCode: $errorCode")
                    releaseMulticastLock()
                }
            }

            nsdManager?.discoverServices(
                SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                nsdDiscoveryListener
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NSD discovery", e)
            releaseMulticastLock()
        }
    }

    fun stopNsdDiscovery() {
        if (!isNsdDiscovering && nsdDiscoveryListener == null) {
            releaseMulticastLock()
            return
        }
        try {
            nsdDiscoveryListener?.let { listener ->
                nsdManager?.stopServiceDiscovery(listener)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping NSD discovery", e)
        } finally {
            nsdDiscoveryListener = null
            isNsdDiscovering = false
            releaseMulticastLock()
        }
    }

    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("UClipMulticastLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (multicastLock?.isHeld == false) {
                multicastLock?.acquire()
                Log.i(TAG, "MulticastLock acquired for mDNS discovery")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire MulticastLock", e)
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (!isNsdDiscovering && !isNsdAdvertising && nsdDiscoveryListener == null && nsdRegistrationListener == null) {
                if (multicastLock?.isHeld == true) {
                    multicastLock?.release()
                    Log.i(TAG, "MulticastLock released")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release MulticastLock", e)
        }
    }

    private fun queueResolveService(serviceInfo: NsdServiceInfo) {
        synchronized(resolveQueue) {
            resolveQueue.add(serviceInfo)
            processNextResolve()
        }
    }

    private fun processNextResolve() {
        synchronized(resolveQueue) {
            if (isResolving || resolveQueue.isEmpty()) return
            val nextService = resolveQueue.poll() ?: return
            isResolving = true
            Log.i(TAG, "NSD Service resolution started for ${nextService.serviceName}")

            val resolveTimeoutJob = scope.launch {
                kotlinx.coroutines.delay(5000)
                synchronized(resolveQueue) {
                    if (isResolving) {
                        Log.w(TAG, "NSD Resolve timed out after 5s for ${nextService.serviceName}")
                        isResolving = false
                        processNextResolve()
                    }
                }
            }

            try {
                nsdManager?.resolveService(nextService, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        resolveTimeoutJob.cancel()
                        Log.e(TAG, "NSD Resolve failed for ${serviceInfo.serviceName} with errorCode: $errorCode")
                        synchronized(resolveQueue) {
                            isResolving = false
                            processNextResolve()
                        }
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        resolveTimeoutJob.cancel()
                        Log.i(TAG, "NSD Service resolved: ${serviceInfo.serviceName}, host=${serviceInfo.host}, port=${serviceInfo.port}")
                        handleResolvedService(serviceInfo)
                        synchronized(resolveQueue) {
                            isResolving = false
                            processNextResolve()
                        }
                    }
                })
            } catch (e: Exception) {
                resolveTimeoutJob.cancel()
                Log.e(TAG, "Failed to invoke resolveService for ${nextService.serviceName}", e)
                isResolving = false
                processNextResolve()
            }
        }
    }

    private fun handleResolvedService(serviceInfo: NsdServiceInfo) {
        val hostAddress = serviceInfo.host?.hostAddress ?: return

        var devId = try {
            val bytes = serviceInfo.attributes["deviceId"]
            bytes?.let { String(it, Charsets.UTF_8) }
        } catch (e: Throwable) { null }

        var devName = try {
            val bytes = serviceInfo.attributes["deviceName"]
            bytes?.let { String(it, Charsets.UTF_8) }
        } catch (e: Throwable) { null }

        if (devId.isNullOrBlank()) {
            devId = serviceInfo.serviceName.removePrefix("UClip_")
        }
        if (devName.isNullOrBlank()) {
            devName = serviceInfo.serviceName.removePrefix("UClip_").replace("_", " ")
        }

        // Filter out local device ID or self-discovery
        if (devId == localDeviceId || serviceInfo.serviceName.contains(localDeviceId)) {
            Log.d(TAG, "Ignoring self-discovered device in handleResolvedService: $devId (localDeviceId: $localDeviceId)")
            return
        }

        Log.i(TAG, "Discovered IP address: $hostAddress for deviceId: $devId ($devName)")

        val discoveredDevice = Device(
            deviceId = devId,
            deviceName = devName,
            deviceType = "PHONE",
            ipAddress = hostAddress,
            isLocalDevice = false,
            isOnline = true,
            isPaired = false
        )

        addDiscoveredDevice(discoveredDevice)
    }

    fun addDiscoveredDevice(device: Device) {
        if (device.deviceId == localDeviceId) return
        val isKnown = isKnownPeer(device.deviceId)
        val current = _discoveredDevices.value.toMutableList()
        val index = current.indexOfFirst { it.deviceId == device.deviceId || (it.ipAddress != null && it.ipAddress == device.ipAddress) }
        val updatedDevice: Device

        if (index >= 0) {
            val existing = current[index]
            val preservedState = if (device.connectionState == ConnectionState.CONNECTED || activeSessions.containsKey(device.deviceId)) {
                ConnectionState.CONNECTED
            } else if (existing.connectionState == ConnectionState.CONNECTED) {
                ConnectionState.CONNECTED
            } else if (existing.connectionState == ConnectionState.CONNECTING || existing.connectionState == ConnectionState.RECONNECTING) {
                existing.connectionState
            } else if (isKnown && (existing.connectionState == ConnectionState.DISCONNECTED || existing.connectionState == ConnectionState.DISCOVERED)) {
                ConnectionState.RECONNECTING
            } else {
                device.connectionState
            }
            updatedDevice = device.copy(
                deviceId = if (device.deviceId.isNotBlank() && device.deviceId != "target_ip" && !device.deviceId.startsWith("dev_192_")) device.deviceId else existing.deviceId,
                isPaired = isKnown,
                connectionState = preservedState
            )
            current[index] = updatedDevice
        } else {
            val initialState = if (device.connectionState == ConnectionState.CONNECTED || activeSessions.containsKey(device.deviceId)) {
                ConnectionState.CONNECTED
            } else if (isKnown && device.connectionState == ConnectionState.DISCOVERED) {
                ConnectionState.RECONNECTING
            } else {
                device.connectionState
            }
            updatedDevice = device.copy(
                isPaired = isKnown,
                connectionState = initialState
            )
            current.add(updatedDevice)
        }
        if (isKnown) {
            DeviceTrustManager.recordPeerSeen(context, updatedDevice.deviceId, updatedDevice.deviceName, updatedDevice.ipAddress)
        }
        _discoveredDevices.value = current

        // Automatic Reconnection Trigger for Known Peers (only when disconnected or reconnecting)
        if (isKnown && updatedDevice.connectionState != ConnectionState.CONNECTED && !activeSessions.containsKey(updatedDevice.deviceId)) {
            triggerAutoReconnectIfEligible(updatedDevice)
        }
    }

    fun triggerAutoReconnectIfEligible(targetDevice: Device) {
        val deviceId = targetDevice.deviceId
        if (!isKnownPeer(deviceId) || deviceId == localDeviceId) return
        if (activeSessions.containsKey(deviceId)) {
            Log.d(TAG, "Device $deviceId already has an active session. No auto-reconnect needed.")
            return
        }

        val currentStatus = _discoveredDevices.value.find { it.deviceId == deviceId }?.connectionState
        if (currentStatus == ConnectionState.CONNECTED || currentStatus == ConnectionState.CONNECTING) {
            return
        }

        val existingJob = reconnectingJobs[deviceId]
        if (existingJob != null && existingJob.isActive) {
            Log.d(TAG, "Auto-reconnect already active for $deviceId. Skipping duplicate trigger.")
            return
        }

        val job = scope.launch(Dispatchers.IO) {
            try {
                var attempt = 0
                val maxAttempts = 3
                val backoffs = listOf(100L, 1000L, 2500L)

                while (isActive && attempt < maxAttempts) {
                    if (activeSessions.containsKey(deviceId)) {
                        Log.i(TAG, "Device $deviceId is connected. Terminating auto-reconnect loop.")
                        break
                    }

                    // Retrieve newest device entry from state flow to ensure latest IP from NSD is used
                    val latestDevice = _discoveredDevices.value.find { it.deviceId == deviceId } ?: targetDevice
                    if (latestDevice.ipAddress.isNullOrBlank()) {
                        Log.w(TAG, "Cannot auto-reconnect to $deviceId: Missing IP address.")
                        break
                    }

                    attempt++
                    Log.i(TAG, "Auto-reconnecting to known peer $deviceId at ${latestDevice.ipAddress} (Attempt $attempt/$maxAttempts)...")
                    updateDeviceConnectionState(deviceId, ConnectionState.RECONNECTING)

                    val success = connectToDevice(latestDevice, fromAutoReconnect = true)
                    if (success) {
                        Log.i(TAG, "Auto-reconnection succeeded for known peer $deviceId.")
                        break
                    } else {
                        Log.w(TAG, "Auto-reconnection attempt $attempt failed for $deviceId.")
                        if (attempt < maxAttempts) {
                            val delayMs = backoffs.getOrElse(attempt) { 2000L }
                            delay(delayMs)
                        }
                    }
                }

                if (!activeSessions.containsKey(deviceId)) {
                    val finalState = _discoveredDevices.value.find { it.deviceId == deviceId }?.connectionState
                    if (finalState != ConnectionState.CONNECTED) {
                        updateDeviceConnectionState(deviceId, ConnectionState.DISCONNECTED)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during auto-reconnection for $deviceId", e)
                if (!activeSessions.containsKey(deviceId)) {
                    updateDeviceConnectionState(deviceId, ConnectionState.DISCONNECTED)
                }
            } finally {
                reconnectingJobs.remove(deviceId)
            }
        }

        reconnectingJobs[deviceId] = job
    }

    fun updateDeviceConnectionState(deviceId: String, state: ConnectionState) {
        val current = _discoveredDevices.value.toMutableList()
        val index = current.indexOfFirst { it.deviceId == deviceId }
        if (index >= 0) {
            val updated = current[index].copy(
                connectionState = state,
                isOnline = state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING || state == ConnectionState.RECONNECTING || state == ConnectionState.DISCOVERED
            )
            current[index] = updated
            _discoveredDevices.value = current
        }
    }

    private fun parseKeyFromMessage(message: String?, key: String): String? {
        if (message.isNullOrBlank()) return null
        try {
            val regex = Regex("""(?i)\b$key\s*[:=]\s*([^;\,\}\s"]+)""")
            val match = regex.find(message)
            if (match != null) {
                return match.groupValues[1].trim()
            }
            if (key == "deviceName") {
                if (message.startsWith("ACK_FROM_")) {
                    return message.removePrefix("ACK_FROM_").trim()
                }
                if (message.startsWith("HELLO_FROM_")) {
                    return message.removePrefix("HELLO_FROM_").trim()
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to parse $key from message: $message", e)
        }
        return null
    }

    var onPeerSessionStateChanged: ((peerDeviceId: String, state: ConnectionState, reason: String?) -> Unit)? = null

    fun getActiveSessionDeviceIds(): Set<String> {
        return activeSessions.keys.toSet()
    }

    fun hasActiveSession(deviceId: String): Boolean {
        return activeSessions.containsKey(deviceId)
    }

    fun reconnectAllTrustedPeers() {
        val trustedPeers = DeviceTrustManager.getAllTrustedPeers()
        for (peer in trustedPeers) {
            val ipHint = peer.lastKnownIpAddress
            if (!ipHint.isNullOrBlank() && !hasActiveSession(peer.peerDeviceId) && peer.peerDeviceId != localDeviceId) {
                val targetDevice = Device(
                    deviceId = peer.peerDeviceId,
                    deviceName = peer.deviceName,
                    deviceType = peer.deviceType,
                    ipAddress = ipHint,
                    isLocalDevice = false,
                    isOnline = false,
                    isPaired = true,
                    connectionState = ConnectionState.DISCONNECTED
                )
                addDiscoveredDevice(targetDevice)
                triggerAutoReconnectIfEligible(targetDevice)
            }
        }
    }

    suspend fun connectToDevice(targetDevice: Device, fromAutoReconnect: Boolean = false): Boolean {
        val deviceId = targetDevice.deviceId
        if (!fromAutoReconnect) {
            reconnectingJobs.remove(deviceId)?.cancel()
        }

        val currentDeviceState = _discoveredDevices.value.find { it.deviceId == deviceId }?.connectionState
        if (currentDeviceState == ConnectionState.CONNECTED && activeSessions.containsKey(deviceId)) {
            Log.i(TAG, "Device $deviceId is already CONNECTED with active session.")
            return true
        }
        if (currentDeviceState == ConnectionState.CONNECTING) {
            Log.i(TAG, "Device $deviceId is currently CONNECTING. Ignoring duplicate connection request.")
            return false
        }

        updateDeviceConnectionState(deviceId, ConnectionState.CONNECTING)
        onPeerSessionStateChanged?.invoke(deviceId, ConnectionState.CONNECTING, "Initiating TCP handshake")

        return withContext(Dispatchers.IO) {
            val rawIp = targetDevice.ipAddress
            if (rawIp.isNullOrBlank()) {
                Log.e(TAG, "Cannot connect to device $deviceId: IP address is missing")
                updateDeviceConnectionState(deviceId, ConnectionState.ERROR)
                onPeerSessionStateChanged?.invoke(deviceId, ConnectionState.ERROR, "Missing IP address")
                return@withContext false
            }

            val targetIp = if (rawIp.contains(":")) rawIp.substringBefore(":") else rawIp
            val targetPort = if (rawIp.contains(":")) rawIp.substringAfter(":").toIntOrNull() ?: port else port

            var socket: Socket? = null
            try {
                socket = Socket()
                socket.connect(InetSocketAddress(targetIp, targetPort), CONNECT_TIMEOUT_MS)
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                val helloMsg = "HELLO deviceId=$localDeviceId;deviceName=${localDeviceName.replace(" ", "_")}"
                writer.println(helloMsg)
                writer.flush()
                Log.d(TAG, "Sent identity handshake to $targetIp:$targetPort: $helloMsg")

                val ackLine = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS.toLong()) {
                    reader.readLine()
                }

                if (ackLine.isNullOrBlank()) {
                    Log.e(TAG, "Handshake failed: No response ACK received from $targetIp:$targetPort")
                    try { socket.close() } catch (_: Throwable) {}
                    updateDeviceConnectionState(deviceId, ConnectionState.ERROR)
                    onPeerSessionStateChanged?.invoke(deviceId, ConnectionState.ERROR, "Handshake timeout or empty response")
                    return@withContext false
                }

                Log.d(TAG, "Received identity ACK from $targetIp:$targetPort: $ackLine")

                val remoteDeviceId = parseKeyFromMessage(ackLine, "deviceId")
                val remoteDeviceName = parseKeyFromMessage(ackLine, "deviceName")

                // Verification requirement: Remote deviceId must match targetDevice.deviceId if target is explicitly named
                if (remoteDeviceId != null && remoteDeviceId != deviceId && deviceId.isNotBlank() && deviceId != "target_ip" && !deviceId.startsWith("dev_192_") && !deviceId.startsWith("dev_10_") && !deviceId.startsWith("dev_127_")) {
                    Log.e(TAG, "Identity mismatch! Expected deviceId '$deviceId', but peer returned '$remoteDeviceId'. Rejecting connection.")
                    try { socket.close() } catch (_: Throwable) {}
                    updateDeviceConnectionState(deviceId, ConnectionState.ERROR)
                    onPeerSessionStateChanged?.invoke(deviceId, ConnectionState.ERROR, "Identity mismatch")
                    return@withContext false
                }

                val finalDeviceId = remoteDeviceId ?: deviceId
                val effectiveDeviceName = remoteDeviceName ?: targetDevice.deviceName
                Log.i(TAG, "Peer identity established for device $finalDeviceId ($effectiveDeviceName). Connection established.")

                // Outbound user connection: record trusted peer authorization and connected timestamp
                DeviceTrustManager.recordPeerConnected(
                    context = context,
                    peerDeviceId = finalDeviceId,
                    deviceName = effectiveDeviceName,
                    ipHint = targetDevice.ipAddress
                )
                addKnownPeer(finalDeviceId, effectiveDeviceName, targetDevice.ipAddress)
                val isKnown = isKnownPeer(finalDeviceId)
                if (isKnown) {
                    reconnectingJobs.remove(finalDeviceId)?.cancel()
                }

                // Switch to persistent monitoring mode (no read timeout on persistent stream)
                socket.soTimeout = 0

                val session = PeerSession(
                    deviceId = finalDeviceId,
                    deviceName = remoteDeviceName ?: targetDevice.deviceName,
                    socket = socket,
                    reader = reader,
                    writer = writer
                )

                activeSessions[finalDeviceId]?.closeSilently()
                activeSessions[finalDeviceId] = session

                val connectedDevice = targetDevice.copy(
                    deviceId = finalDeviceId,
                    deviceName = remoteDeviceName ?: targetDevice.deviceName,
                    isPaired = isKnown,
                    connectionState = ConnectionState.CONNECTED
                )
                addDiscoveredDevice(connectedDevice)
                updateDeviceConnectionState(finalDeviceId, ConnectionState.CONNECTED)
                onPeerSessionStateChanged?.invoke(finalDeviceId, ConnectionState.CONNECTED, null)
                startSessionMonitoring(session)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Connection attempt failed to $deviceId at $targetIp:$targetPort: ${e.message}")
                try { socket?.close() } catch (_: Throwable) {}
                updateDeviceConnectionState(deviceId, ConnectionState.ERROR)
                onPeerSessionStateChanged?.invoke(deviceId, ConnectionState.ERROR, e.message)
                false
            }
        }
    }

    suspend fun disconnectFromDevice(deviceId: String) {
        withContext(Dispatchers.IO) {
            reconnectingJobs.remove(deviceId)?.cancel()
            val session = activeSessions.remove(deviceId)
            if (session != null) {
                try {
                    session.writer.println("DISCONNECT")
                    session.writer.flush()
                } catch (_: Throwable) {}
                session.closeSilently()
                Log.i(TAG, "Explicitly disconnected from device $deviceId")
            }
            updateDeviceConnectionState(deviceId, ConnectionState.DISCONNECTED)
            onPeerSessionStateChanged?.invoke(deviceId, ConnectionState.DISCONNECTED, "Explicit local disconnect")
        }
    }

    fun closeAllSessions() {
        val currentSessions = activeSessions.values.toList()
        activeSessions.clear()
        for (session in currentSessions) {
            try {
                session.writer.println("DISCONNECT")
                session.writer.flush()
            } catch (_: Throwable) {}
            session.closeSilently()
            updateDeviceConnectionState(session.deviceId, ConnectionState.DISCONNECTED)
            onPeerSessionStateChanged?.invoke(session.deviceId, ConnectionState.DISCONNECTED, "All sessions closed")
        }
    }

    fun onNetworkLost() {
        Log.i(TAG, "onNetworkLost: Network interface lost. Stopping discovery and disconnecting all active peers.")
        try {
            stopNsdDiscovery()
        } catch (_: Throwable) {}
        try {
            stopNsdAdvertisement()
        } catch (_: Throwable) {}

        reconnectingJobs.values.forEach { it.cancel() }
        reconnectingJobs.clear()

        val sessionsToClose = activeSessions.values.toList()
        activeSessions.clear()
        for (session in sessionsToClose) {
            session.closeSilently()
            onPeerSessionStateChanged?.invoke(session.deviceId, ConnectionState.DISCONNECTED, "Network interface lost")
        }

        val updated = _discoveredDevices.value.map {
            it.copy(
                connectionState = ConnectionState.DISCONNECTED,
                isOnline = false
            )
        }
        _discoveredDevices.value = updated
    }

    internal suspend fun processIncomingClipboardItem(item: ClipboardItem, writer: PrintWriter? = null): Boolean {
        val senderDeviceId = item.sourceDeviceId.trim()
        Log.i("SyncDiagnostics", "[SYNC_PATH_10_DESERIALIZATION] Deserialized item ID=${item.id}, source=$senderDeviceId (${item.sourceDeviceName}), length=${item.content.length}, type=${item.type}")

        // 0. Authoritative Inbound PAUSE Gate
        val policy = SyncPolicyManager.getPolicy()
        if (policy.isSyncPaused) {
            Log.w(TAG, "Rejected incoming clipboard payload from $senderDeviceId: Local synchronization is PAUSED.")
            try {
                writer?.println("ERROR_PAUSED")
                writer?.flush()
            } catch (_: Throwable) {}
            Log.i("SyncDiagnostics", "[SYNC_PATH_8_TCP_ACK] Receiver rejected incoming payload with ERROR_PAUSED id=${item.id}")
            return false
        }

        // 0b. Blocked Device Gate
        if (policy.blockedDeviceIds.contains(senderDeviceId)) {
            Log.w(TAG, "Rejected incoming clipboard payload from blocked peer: $senderDeviceId")
            try {
                writer?.println("ERROR_BLOCKED")
                writer?.flush()
            } catch (_: Throwable) {}
            return false
        }

        // 1. Validate Sender Device ID
        if (senderDeviceId.isBlank()) {
            Log.w(TAG, "Rejected clipboard payload with empty sender device ID")
            try {
                writer?.println("ERROR_INVALID_SENDER")
                writer?.flush()
            } catch (_: Throwable) {}
            return false
        }

        // 2. Self Device & Echo Loop Prevention
        val isSelfEcho = senderDeviceId == localDeviceId
        if (isSelfEcho) {
            Log.w(TAG, "Ignoring echo item from self device ID: $senderDeviceId")
            try {
                writer?.println("ACK_ECHO_SKIPPED")
                writer?.flush()
            } catch (_: Throwable) {}
            Log.i("SyncDiagnostics", "[SYNC_PATH_8_TCP_ACK] Receiver sent ACK_ECHO_SKIPPED id=${item.id}")
            return false
        }

        // 3. SECURE AUTHORIZATION CHECK («Discovery ≠ Authorization»)
        val isAuthorized = isKnownPeer(senderDeviceId)
        if (!isAuthorized) {
            // SECURITY MANDATE: Unknown peers must remain unauthorized.
            // Do NOT insert into database, do NOT pass to ClipboardCoreManager, do NOT mark sender as paired.
            // Diagnostic log without leaking clipboard content.
            Log.w(TAG, "Rejected clipboard payload from unauthorized/unpaired peer device: $senderDeviceId")
            try {
                writer?.println("ERROR_UNAUTHORIZED")
                writer?.flush()
            } catch (_: Throwable) {}
            Log.i("SyncDiagnostics", "[SYNC_PATH_8_TCP_ACK] Receiver rejected unauthorized peer $senderDeviceId with ERROR_UNAUTHORIZED")
            return false
        }

        // 4. Validate SHA-256 Hash
        val computedHash = ClipboardCoreManager.computeSha256(item.content)
        val hashMatches = computedHash.equals(item.hash, ignoreCase = true)

        if (!hashMatches) {
            Log.e(TAG, "SHA-256 hash validation failed for item [${item.id}]. Expected: ${item.hash}, Computed: $computedHash")
            try {
                writer?.println("ERROR_HASH_MISMATCH")
                writer?.flush()
            } catch (_: Throwable) {}
            Log.i("SyncDiagnostics", "[SYNC_PATH_8_TCP_ACK] Receiver sent ERROR_HASH_MISMATCH id=${item.id}")
            return false
        }

        // 5. Deduplication Check
        val isDuplicate = synchronized(recentlyProcessedHashes) {
            if (recentlyProcessedHashes.contains(item.hash)) {
                true
            } else {
                recentlyProcessedHashes.add(item.hash)
                if (recentlyProcessedHashes.size > 500) {
                    val iterator = recentlyProcessedHashes.iterator()
                    if (iterator.hasNext()) {
                        iterator.next()
                        iterator.remove()
                    }
                }
                false
            }
        }

        Log.i("SyncDiagnostics", "[SYNC_PATH_11_HASH_VALIDATION] Hash validation: computed=${computedHash.take(8)}, expected=${item.hash.take(8)}, match=$hashMatches, isSelfEcho=$isSelfEcho, isDuplicate=$isDuplicate")

        if (isDuplicate) {
            Log.w(TAG, "Ignoring duplicate item with hash prefix: ${item.hash.take(8)}")
            try {
                writer?.println("ACK_DUPLICATE_SKIPPED")
                writer?.flush()
            } catch (_: Throwable) {}
            Log.i("SyncDiagnostics", "[SYNC_PATH_8_TCP_ACK] Receiver sent ACK_DUPLICATE_SKIPPED id=${item.id}")
            return false
        }

        // 6. Update authorized peer device in registry
        val peerDevice = Device(
            deviceId = item.sourceDeviceId,
            deviceName = item.sourceDeviceName,
            deviceType = "PHONE",
            isLocalDevice = false,
            isOnline = true,
            isPaired = true,
            connectionState = ConnectionState.CONNECTED
        )
        addDiscoveredDevice(peerDevice)

        // 7. Accepted — Emit to incoming pipeline
        try {
            writer?.println("ACK_OK")
            writer?.flush()
        } catch (_: Throwable) {}
        Log.i("SyncDiagnostics", "[SYNC_PATH_8_TCP_ACK] Receiver confirmed item ${item.id} with ACK_OK id=${item.id}")
        _incomingItems.emit(item)
        _incomingMessages.emit("Received ClipboardItem [ID: ${item.id}, Source: ${item.sourceDeviceName}, ContentLength: ${item.content.length}]")
        Log.i(TAG, "Successfully received and validated remote ClipboardItem [ID: ${item.id}, HashPrefix: ${item.hash.take(8)}]")
        return true
    }

    private fun startSessionMonitoring(session: PeerSession) {
        scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val line = try {
                        session.reader.readLine()
                    } catch (e: Exception) {
                        Log.d(TAG, "Session read exception for ${session.deviceId}: ${e.message}")
                        break
                    } ?: break // EOF / Socket closed by remote

                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue

                    if (trimmed == "DISCONNECT" || trimmed == "BYE") {
                        Log.i(TAG, "Peer ${session.deviceId} sent explicit disconnect signal.")
                        break
                    }

                    if (trimmed == "PING") {
                        try {
                            session.writer.println("PONG")
                            session.writer.flush()
                        } catch (_: Throwable) {}
                        continue
                    }

                    if (trimmed == "PONG") {
                        Log.d(TAG, "Received PONG keepalive from ${session.deviceId}")
                        continue
                    }

                    if (trimmed.startsWith("{")) {
                        try {
                            val envelope = ProtocolEnvelope.parse(trimmed)
                            if (envelope != null && envelope.messageType == ProtocolMessageType.CLIPBOARD_ITEM) {
                                val item = if (envelope.payload.startsWith("{")) {
                                    parseClipboardItemFromJson(envelope.payload)
                                } else null
                                if (item != null) {
                                    processIncomingClipboardItem(item, session.writer)
                                    continue
                                }
                            } else if (envelope != null && envelope.messageType == ProtocolMessageType.DISCONNECT) {
                                Log.i(TAG, "Peer ${session.deviceId} sent DISCONNECT envelope")
                                break
                            }

                            // Fallback direct JSON parsing
                            val item = parseClipboardItemFromJson(trimmed)
                            if (item != null) {
                                processIncomingClipboardItem(item, session.writer)
                                continue
                            } else {
                                Log.w(TAG, "Unrecognized JSON payload received over session ${session.deviceId}")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse incoming JSON frame over session ${session.deviceId}: ${e.message}")
                        }
                        continue
                    }

                    if (trimmed.startsWith("ACK_") || trimmed.startsWith("ACK ") || trimmed.startsWith("ERROR_")) {
                        Log.i("SyncDiagnostics", "[SYNC_PATH_8_TCP_ACK] Received session ACK from ${session.deviceId}: $trimmed")
                        val idParam = if (trimmed.contains("id=")) trimmed.substringAfter("id=").trim().substringBefore(" ") else ""
                        if (idParam.isNotBlank() && inFlightAcks.containsKey(idParam)) {
                            inFlightAcks[idParam]?.complete(trimmed)
                        } else {
                            inFlightAcks.values.forEach { it.complete(trimmed) }
                        }
                    }
                    _incomingMessages.emit("Session [${session.deviceId}]: $trimmed")
                }
            } catch (e: Exception) {
                Log.d(TAG, "Session read loop ended for ${session.deviceId}: ${e.message}")
            } finally {
                Log.i(TAG, "Peer session ended for device ${session.deviceId}")
                session.closeSilently()
                val removed = activeSessions.remove(session.deviceId, session)
                if (removed) {
                    updateDeviceConnectionState(session.deviceId, ConnectionState.DISCONNECTED)
                    onPeerSessionStateChanged?.invoke(session.deviceId, ConnectionState.DISCONNECTED, "Remote disconnect or socket closed")
                }
            }
        }
    }

    private fun removeDiscoveredDeviceByServiceName(serviceName: String) {
        val current = _discoveredDevices.value.toMutableList()
        current.removeAll { 
            serviceName.contains(it.deviceId) && 
            it.connectionState != ConnectionState.CONNECTED && 
            !activeSessions.containsKey(it.deviceId) 
        }
        _discoveredDevices.value = current
    }

    fun clearDiscoveredDevices() {
        _discoveredDevices.value = emptyList()
    }

    private suspend fun handleIncomingSocket(socket: Socket) {
        withContext(Dispatchers.IO) {
            var isPersistentSession = false
            var sessionToStart: PeerSession? = null
            try {
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)

                val message = reader.readLine()
                if (message != null) {
                    val trimmed = message.trim()
                    val remoteHost = socket.inetAddress?.hostAddress ?: "127.0.0.1"

                    if (trimmed.startsWith("{")) {
                        Log.i("SyncDiagnostics", "[SYNC_PATH_9_RECEPTION] Received raw JSON item from TCP $remoteHost:${socket.port}")
                        val item = parseClipboardItemFromJson(trimmed)
                        if (item != null) {
                            if (item.sourceDeviceId != localDeviceId) {
                                val isKnown = isKnownPeer(item.sourceDeviceId)
                                val peerDevice = Device(
                                    deviceId = item.sourceDeviceId,
                                    deviceName = item.sourceDeviceName,
                                    ipAddress = remoteHost,
                                    isLocalDevice = false,
                                    isOnline = true,
                                    isPaired = isKnown,
                                    connectionState = if (isKnown) ConnectionState.CONNECTED else ConnectionState.DISCOVERED
                                )
                                addDiscoveredDevice(peerDevice)
                            }
                            processIncomingClipboardItem(item, writer)
                            return@withContext
                        }
                    }

                    // Handshake or diagnostic message fallback
                    Log.d(TAG, "Received message: $message from ${socket.remoteSocketAddress}")
                    _incomingMessages.emit(message)

                    val peerDeviceId = parseKeyFromMessage(trimmed, "deviceId")
                    val peerDeviceName = parseKeyFromMessage(trimmed, "deviceName") ?: "Remote Device"

                    val response = if (trimmed.contains("deviceId=")) {
                        "ACK deviceId=$localDeviceId;deviceName=${localDeviceName.replace(" ", "_")}"
                    } else if (trimmed.startsWith("HELLO_")) {
                        val devName = if (localDeviceName.isNullOrBlank()) "DEVICE" else localDeviceName.replace(" ", "_")
                        "ACK_FROM_$devName"
                    } else {
                        "ACK_OK"
                    }
                    writer.println(response)
                    writer.flush()
                    Log.d(TAG, "Sent handshake ACK: $response")

                    if (trimmed.startsWith("HELLO") && !peerDeviceId.isNullOrBlank() && peerDeviceId != localDeviceId) {
                        isPersistentSession = true
                        socket.soTimeout = 0

                        val session = PeerSession(
                            deviceId = peerDeviceId,
                            deviceName = peerDeviceName,
                            socket = socket,
                            reader = reader,
                            writer = writer
                        )
                        activeSessions[peerDeviceId]?.closeSilently()
                        activeSessions[peerDeviceId] = session
                        sessionToStart = session

                        // Handshake establishes connection, NOT authorization:
                        val isKnown = isKnownPeer(peerDeviceId)
                        if (isKnown) {
                            DeviceTrustManager.recordPeerConnected(context, peerDeviceId, peerDeviceName, remoteHost)
                            reconnectingJobs.remove(peerDeviceId)?.cancel()
                        }

                        val peerDevice = Device(
                            deviceId = peerDeviceId,
                            deviceName = peerDeviceName,
                            ipAddress = remoteHost,
                            isLocalDevice = false,
                            isOnline = true,
                            isPaired = isKnown,
                            connectionState = ConnectionState.CONNECTED
                        )
                        addDiscoveredDevice(peerDevice)
                        updateDeviceConnectionState(peerDeviceId, ConnectionState.CONNECTED)
                        onPeerSessionStateChanged?.invoke(peerDeviceId, ConnectionState.CONNECTED, null)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling incoming socket connection: ${e.message}")
            } finally {
                if (!isPersistentSession) {
                    try {
                        socket.close()
                    } catch (_: Throwable) {}
                } else if (sessionToStart != null) {
                    startSessionMonitoring(sessionToStart)
                }
            }
        }
    }

    /**
     * Direct local handshake test connection method.
     * Connects to target IP and port, sends test message, waits for ACK response.
     */
    suspend fun sendHandshake(targetIp: String, targetPort: Int = port, message: String): String? {
        return withContext(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                socket = Socket()
                socket.connect(InetSocketAddress(targetIp, targetPort), CONNECT_TIMEOUT_MS)
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                writer.println(message)
                writer.flush()
                Log.d(TAG, "Sent handshake message '$message' to $targetIp:$targetPort")

                val ack = reader.readLine()
                Log.d(TAG, "Received ACK '$ack' from $targetIp:$targetPort")

                val remoteDeviceId = parseKeyFromMessage(ack ?: "", "deviceId")
                val remoteDeviceName = parseKeyFromMessage(ack ?: "", "deviceName")
                val devId = remoteDeviceId ?: "dev_${targetIp.replace(".", "_")}"
                val devName = remoteDeviceName ?: "Device at $targetIp"

                val isKnown = if (remoteDeviceId != null) isKnownPeer(remoteDeviceId) else false
                val discoveredDevice = Device(
                    deviceId = devId,
                    deviceName = devName,
                    ipAddress = targetIp,
                    isLocalDevice = false,
                    isOnline = true,
                    isPaired = isKnown,
                    connectionState = ConnectionState.DISCOVERED
                )
                addDiscoveredDevice(discoveredDevice)

                ack
            } catch (e: Exception) {
                Log.e(TAG, "Handshake failed to $targetIp:$targetPort: ${e.message}")
                null
            } finally {
                try {
                    socket?.close()
                } catch (_: Throwable) {}
            }
        }
    }

    override suspend fun startTransport() {
        startServer()
        startNsdDiscovery()
    }

    override suspend fun stopTransport() {
        stopNsdDiscovery()
        stopNsdAdvertisement()
        closeAllSessions()
        stopServer()
    }

    override suspend fun sendItem(item: ClipboardItem, targetDeviceId: String): Boolean {
        return withContext(Dispatchers.IO) {
            val policy = SyncPolicyManager.getPolicy()
            if (policy.isSyncPaused) {
                Log.w(TAG, "LocalWifiTransport.sendItem rejected: Synchronization is currently PAUSED.")
                return@withContext false
            }

            val jsonPayload = item.toJsonString()
            Log.i("SyncDiagnostics", "[SYNC_PATH_6_MESSAGE_CREATION] Serialized item ${item.id} to JSON payload (${jsonPayload.toByteArray(Charsets.UTF_8).size} bytes, hash=${item.hash.take(8)})")

            val isExplicitTarget = targetDeviceId.isNotBlank() && targetDeviceId != "ALL"

            if (isExplicitTarget) {
                // If it's a direct IP string (e.g. "127.0.0.1:55301")
                if (targetDeviceId.contains(".")) {
                    val rawIp = targetDeviceId
                    val targetIp = if (rawIp.contains(":")) rawIp.substringBefore(":") else rawIp
                    val targetPort = if (rawIp.contains(":")) rawIp.substringAfter(":").toIntOrNull() ?: port else port
                    return@withContext sendDirectSocket(targetIp, targetPort, jsonPayload, item.id)
                }

                // Try active persistent PeerSession first
                val activeSession = activeSessions[targetDeviceId]
                if (activeSession != null) {
                    val ackDeferred = kotlinx.coroutines.CompletableDeferred<String>()
                    inFlightAcks[item.id] = ackDeferred
                    try {
                        Log.i("SyncDiagnostics", "[SYNC_PATH_7_TCP_WRITE] Writing item ${item.id} over active PeerSession to ${activeSession.deviceId}")
                        activeSession.writer.println(jsonPayload)
                        activeSession.writer.flush()
                        val ack = withTimeoutOrNull(SOCKET_TIMEOUT_MS.toLong()) {
                            ackDeferred.await()
                        }
                        if (ack == "ACK_OK" || ack == "ACK_DUPLICATE_SKIPPED" || ack == "ACK_ECHO_SKIPPED" || ack?.startsWith("ACK_") == true || ack?.startsWith("ACK") == true) {
                            Log.i("SyncDiagnostics", "[SYNC_PATH_RESULT] Session ACK confirmed item ${item.id} with '$ack'")
                            return@withContext true
                        } else {
                            Log.w("SyncDiagnostics", "[SYNC_PATH_RESULT] Session ACK failed/timeout for item ${item.id}: '$ack'")
                            return@withContext false
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to write ClipboardItem ${item.id} via PeerSession to ${activeSession.deviceId}: ${e.message}")
                        activeSession.closeSilently()
                        activeSessions.remove(targetDeviceId, activeSession)
                        updateDeviceConnectionState(targetDeviceId, ConnectionState.DISCONNECTED)
                        onPeerSessionStateChanged?.invoke(targetDeviceId, ConnectionState.DISCONNECTED, "Write failure on active session")
                    } finally {
                        inFlightAcks.remove(item.id)
                    }
                }

                // Fallback to direct TCP connection using discovered IP
                val targetDevice = _discoveredDevices.value.find { it.deviceId == targetDeviceId && !it.ipAddress.isNullOrBlank() }
                if (targetDevice == null || targetDevice.ipAddress.isNullOrBlank()) {
                    Log.w(TAG, "Cannot send item ${item.id} to $targetDeviceId: No active session and no valid discovered IP found")
                    return@withContext false
                }

                val rawIp = targetDevice.ipAddress ?: ""
                val targetIp = if (rawIp.contains(":")) rawIp.substringBefore(":") else rawIp
                val targetPort = if (rawIp.contains(":")) rawIp.substringAfter(":").toIntOrNull() ?: port else port
                return@withContext sendDirectSocket(targetIp, targetPort, jsonPayload, item.id)
            } else {
                // BROADCAST / ALL
                var sentSuccessfully = false

                // 1. Active Sessions
                val sessionList = activeSessions.values.toList()
                for (session in sessionList) {
                    try {
                        Log.i("SyncDiagnostics", "[SYNC_PATH_7_TCP_WRITE] Writing item ${item.id} over active PeerSession to ${session.deviceId}")
                        session.writer.println(jsonPayload)
                        session.writer.flush()
                        Log.i(TAG, "Sent ClipboardItem ${item.id} via active PeerSession to ${session.deviceId}")
                        sentSuccessfully = true
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send ClipboardItem ${item.id} via PeerSession to ${session.deviceId}: ${e.message}")
                        session.closeSilently()
                        activeSessions.remove(session.deviceId, session)
                        updateDeviceConnectionState(session.deviceId, ConnectionState.DISCONNECTED)
                        onPeerSessionStateChanged?.invoke(session.deviceId, ConnectionState.DISCONNECTED, "Write failure on broadcast")
                    }
                }

                // 2. Discovered Devices without active session
                val unestablishedTargets = _discoveredDevices.value.filter {
                    !it.ipAddress.isNullOrBlank() && it.deviceId != localDeviceId && !activeSessions.containsKey(it.deviceId)
                }

                for (device in unestablishedTargets) {
                    val rawIp = device.ipAddress ?: continue
                    val targetIp = if (rawIp.contains(":")) rawIp.substringBefore(":") else rawIp
                    val targetPort = if (rawIp.contains(":")) rawIp.substringAfter(":").toIntOrNull() ?: port else port
                    val success = sendDirectSocket(targetIp, targetPort, jsonPayload, item.id)
                    if (success) sentSuccessfully = true
                }

                sentSuccessfully
            }
        }
    }

    private suspend fun sendDirectSocket(targetIp: String, targetPort: Int, jsonPayload: String, itemId: String): Boolean {
        val policy = SyncPolicyManager.getPolicy()
        if (policy.isSyncPaused) {
            Log.w(TAG, "sendDirectSocket rejected: Synchronization is currently PAUSED.")
            return false
        }
        var socket: Socket? = null
        return try {
            Log.i("SyncDiagnostics", "[SYNC_PATH_7_TCP_WRITE] Connecting TCP to $targetIp:$targetPort for item $itemId...")
            socket = Socket()
            socket.connect(InetSocketAddress(targetIp, targetPort), CONNECT_TIMEOUT_MS)
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val writer = PrintWriter(socket.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

            writer.println(jsonPayload)
            writer.flush()
            Log.i("SyncDiagnostics", "[SYNC_PATH_7_TCP_WRITE] Sent ClipboardItem $itemId to $targetIp:$targetPort, waiting for receiver ACK...")

            val ack = reader.readLine()
            Log.i("SyncDiagnostics", "[SYNC_PATH_8_TCP_ACK] Received receiver confirmation '$ack' for item $itemId from $targetIp:$targetPort")

            if (ack == "ACK_OK" || ack == "ACK_DUPLICATE_SKIPPED" || ack == "ACK_ECHO_SKIPPED" || ack?.startsWith("ACK_") == true) {
                Log.i("SyncDiagnostics", "[SYNC_PATH_RESULT] Remote receiver confirmed processing item $itemId with '$ack'")
                true
            } else {
                Log.w("SyncDiagnostics", "[SYNC_PATH_RESULT] Receiver responded with error/unexpected ACK: '$ack'")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send ClipboardItem $itemId to $targetIp:$targetPort: ${e.message}")
            false
        } finally {
            try {
                socket?.close()
            } catch (_: Throwable) {}
        }
    }

    override fun observeIncomingItems(): Flow<ClipboardItem> {
        return _incomingItems.asSharedFlow()
    }
}
