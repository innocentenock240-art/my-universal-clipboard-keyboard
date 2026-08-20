package com.example.core.identity

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimum authoritative information needed for recognition, trust persistence, and automatic reconnection.
 *
 * NOTE: [lastKnownIpAddress] is strictly a network connection hint and MUST NEVER be used as the peer's identity.
 * The authoritative stable identity is [peerDeviceId].
 */
data class TrustedPeerRecord(
    val peerDeviceId: String,
    val deviceName: String,
    val deviceType: String = "PHONE",
    val platform: String = "ANDROID",
    val isTrusted: Boolean = true,
    val firstTrustedTimestamp: Long = System.currentTimeMillis(),
    val lastSeenTimestamp: Long = System.currentTimeMillis(),
    val lastConnectedTimestamp: Long = 0L,
    val lastKnownIpAddress: String? = null // Connection hint ONLY, not identity
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("peerDeviceId", peerDeviceId)
            put("deviceName", deviceName)
            put("deviceType", deviceType)
            put("platform", platform)
            put("isTrusted", isTrusted)
            put("firstTrustedTimestamp", firstTrustedTimestamp)
            put("lastSeenTimestamp", lastSeenTimestamp)
            put("lastConnectedTimestamp", lastConnectedTimestamp)
            put("lastKnownIpAddress", lastKnownIpAddress ?: "")
        }
    }

    companion object {
        fun fromJson(json: JSONObject): TrustedPeerRecord? {
            val id = json.optString("peerDeviceId", "").trim()
            if (id.isBlank()) return null
            return TrustedPeerRecord(
                peerDeviceId = id,
                deviceName = json.optString("deviceName", "Remote Device"),
                deviceType = json.optString("deviceType", "PHONE"),
                platform = json.optString("platform", "ANDROID"),
                isTrusted = json.optBoolean("isTrusted", true),
                firstTrustedTimestamp = json.optLong("firstTrustedTimestamp", System.currentTimeMillis()),
                lastSeenTimestamp = json.optLong("lastSeenTimestamp", System.currentTimeMillis()),
                lastConnectedTimestamp = json.optLong("lastConnectedTimestamp", 0L),
                lastKnownIpAddress = json.optString("lastKnownIpAddress", "").takeIf { it.isNotBlank() }
            )
        }
    }
}

/**
 * Single Authoritative Source of Truth for Trusted Peer Persistence and Recognition.
 *
 * Persists trusted peer records in private SharedPreferences (`uclip_device_prefs`),
 * maintains an in-memory thread-safe cache, supports migration from legacy string sets,
 * and survives application process restarts.
 */
object DeviceTrustManager {
    private const val TAG = "DeviceTrustManager"
    private const val PREFS_NAME = "uclip_device_prefs"
    private const val KEY_TRUSTED_PEERS_JSON = "trusted_peer_records_v1"
    private const val KEY_LEGACY_KNOWN_PEERS = "known_peer_device_ids"

    private val inMemoryTrustedPeers = ConcurrentHashMap<String, TrustedPeerRecord>()
    private val _trustedPeersState = kotlinx.coroutines.flow.MutableStateFlow<List<TrustedPeerRecord>>(emptyList())
    val trustedPeersState: kotlinx.coroutines.flow.StateFlow<List<TrustedPeerRecord>> = _trustedPeersState

    private fun updateStateFlow() {
        _trustedPeersState.value = inMemoryTrustedPeers.values.filter { it.isTrusted }.toList()
    }
    @Volatile
    private var isInitialized = false

    /**
     * Initializes and loads trusted peer records from persistent storage.
     */
    fun init(context: Context?) {
        if (context == null) return
        synchronized(this) {
            if (isInitialized) return
            loadFromStorage(context)
            updateStateFlow()
            isInitialized = true
        }
    }

    private fun loadFromStorage(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonString = prefs.getString(KEY_TRUSTED_PEERS_JSON, null)

            if (!jsonString.isNullOrBlank()) {
                val jsonArray = JSONArray(jsonString)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val record = TrustedPeerRecord.fromJson(obj)
                    if (record != null && record.isTrusted) {
                        inMemoryTrustedPeers[record.peerDeviceId] = record
                    }
                }
                Log.i(TAG, "Loaded ${inMemoryTrustedPeers.size} trusted peer records from JSON storage.")
            } else {
                // Backward compatibility migration from legacy String Set
                val legacySet = prefs.getStringSet(KEY_LEGACY_KNOWN_PEERS, null)
                if (!legacySet.isNullOrEmpty()) {
                    Log.i(TAG, "Migrating ${legacySet.size} legacy known peer IDs to structured trusted peer records.")
                    for (legacyId in legacySet) {
                        if (legacyId.isNotBlank()) {
                            val record = TrustedPeerRecord(
                                peerDeviceId = legacyId,
                                deviceName = "Paired Device",
                                isTrusted = true
                            )
                            inMemoryTrustedPeers[legacyId] = record
                        }
                    }
                    saveToStorage(context)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to load trusted peer records from storage", e)
        }
    }

    private fun saveToStorage(context: Context?) {
        updateStateFlow()
        if (context == null) return
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonArray = JSONArray()
            val idSet = HashSet<String>()

            inMemoryTrustedPeers.values.filter { it.isTrusted }.forEach { record ->
                jsonArray.put(record.toJson())
                idSet.add(record.peerDeviceId)
            }

            prefs.edit()
                .putString(KEY_TRUSTED_PEERS_JSON, jsonArray.toString())
                .putStringSet(KEY_LEGACY_KNOWN_PEERS, idSet)
                .apply()

            Log.d(TAG, "Persisted ${inMemoryTrustedPeers.size} trusted peer record(s) to storage.")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to save trusted peer records to storage", e)
        }
    }

    /**
     * Checks if a device ID is an authorized/trusted peer.
     */
    fun isPeerTrusted(peerDeviceId: String): Boolean {
        if (peerDeviceId.isBlank()) return false
        val record = inMemoryTrustedPeers[peerDeviceId]
        return record != null && record.isTrusted
    }

    /**
     * Retrieves the trusted peer record for a given device ID.
     */
    fun getTrustedPeer(peerDeviceId: String): TrustedPeerRecord? {
        return inMemoryTrustedPeers[peerDeviceId]
    }

    /**
     * Returns all currently trusted peer records.
     */
    fun getAllTrustedPeers(): List<TrustedPeerRecord> {
        return inMemoryTrustedPeers.values.filter { it.isTrusted }.toList()
    }

    /**
     * Returns all trusted peer device IDs.
     */
    fun getTrustedPeerIds(): Set<String> {
        return inMemoryTrustedPeers.filterValues { it.isTrusted }.keys.toSet()
    }

    /**
     * Records or updates trust for a peer device upon successful authorization or manual connection.
     */
    fun recordPeerTrust(
        context: Context?,
        peerDeviceId: String,
        deviceName: String,
        deviceType: String = "PHONE",
        platform: String = "ANDROID",
        ipHint: String? = null
    ): TrustedPeerRecord {
        if (peerDeviceId.isBlank()) throw IllegalArgumentException("peerDeviceId cannot be blank")

        val existing = inMemoryTrustedPeers[peerDeviceId]
        val now = System.currentTimeMillis()
        val updated = if (existing != null) {
            existing.copy(
                deviceName = if (deviceName.isNotBlank() && deviceName != "Remote Device") deviceName else existing.deviceName,
                deviceType = if (deviceType.isNotBlank()) deviceType else existing.deviceType,
                platform = if (platform.isNotBlank()) platform else existing.platform,
                isTrusted = true,
                lastSeenTimestamp = now,
                lastKnownIpAddress = ipHint ?: existing.lastKnownIpAddress
            )
        } else {
            TrustedPeerRecord(
                peerDeviceId = peerDeviceId,
                deviceName = if (deviceName.isNotBlank()) deviceName else "Remote Device",
                deviceType = deviceType,
                platform = platform,
                isTrusted = true,
                firstTrustedTimestamp = now,
                lastSeenTimestamp = now,
                lastKnownIpAddress = ipHint
            )
        }

        inMemoryTrustedPeers[peerDeviceId] = updated
        saveToStorage(context)
        Log.i(TAG, "Recorded peer trust for $peerDeviceId ($deviceName). Total trusted: ${inMemoryTrustedPeers.size}")
        return updated
    }

    /**
     * Updates the last seen timestamp and connection hint for a known peer.
     */
    fun recordPeerSeen(
        context: Context?,
        peerDeviceId: String,
        deviceName: String? = null,
        ipHint: String? = null
    ) {
        val existing = inMemoryTrustedPeers[peerDeviceId] ?: return
        val now = System.currentTimeMillis()
        val updated = existing.copy(
            deviceName = if (!deviceName.isNullOrBlank() && deviceName != "Remote Device") deviceName else existing.deviceName,
            lastSeenTimestamp = now,
            lastKnownIpAddress = ipHint ?: existing.lastKnownIpAddress
        )
        inMemoryTrustedPeers[peerDeviceId] = updated
        saveToStorage(context)
    }

    /**
     * Updates the last connected timestamp for a known peer.
     */
    fun recordPeerConnected(
        context: Context?,
        peerDeviceId: String,
        deviceName: String? = null,
        ipHint: String? = null
    ) {
        val now = System.currentTimeMillis()
        val existing = inMemoryTrustedPeers[peerDeviceId]
        if (existing != null) {
            val updated = existing.copy(
                deviceName = if (!deviceName.isNullOrBlank() && deviceName != "Remote Device") deviceName else existing.deviceName,
                lastSeenTimestamp = now,
                lastConnectedTimestamp = now,
                lastKnownIpAddress = ipHint ?: existing.lastKnownIpAddress
            )
            inMemoryTrustedPeers[peerDeviceId] = updated
            saveToStorage(context)
        } else {
            // First time connection establishes trust
            recordPeerTrust(
                context = context,
                peerDeviceId = peerDeviceId,
                deviceName = deviceName ?: "Remote Device",
                ipHint = ipHint
            )
        }
    }

    /**
     * Revokes trust for a peer device.
     */
    fun revokePeerTrust(context: Context?, peerDeviceId: String) {
        val removed = inMemoryTrustedPeers.remove(peerDeviceId)
        if (removed != null) {
            saveToStorage(context)
            Log.i(TAG, "Revoked peer trust for $peerDeviceId")
        }
    }

    /**
     * Clears all trusted peers.
     */
    fun clearAllTrustedPeers(context: Context? = null) {
        inMemoryTrustedPeers.clear()
        saveToStorage(context)
        Log.i(TAG, "Cleared all trusted peer records.")
    }

    /**
     * For test simulation only: resets in-memory cache and reloads from context.
     */
    fun resetCacheForTesting(context: Context? = null) {
        synchronized(this) {
            inMemoryTrustedPeers.clear()
            isInitialized = false
            if (context != null) {
                init(context)
            }
        }
    }
}
