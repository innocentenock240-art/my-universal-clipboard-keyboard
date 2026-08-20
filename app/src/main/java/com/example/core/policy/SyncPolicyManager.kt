package com.example.core.policy

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * Single Authoritative Manager for Synchronization Policy.
 * Persists policy configuration across app restarts and synchronizes state between
 * MainViewModel, App UI screens, and the IME Keyboard Service.
 */
object SyncPolicyManager {
    private const val TAG = "SyncPolicyManager"
    private const val PREFS_NAME = "uclip_sync_policy_prefs"
    private const val KEY_AUTO_SYNC = "is_auto_sync_enabled"
    private const val KEY_SYNC_PAUSED = "is_sync_paused"
    private const val KEY_DEFAULT_SCOPE = "default_scope"
    private const val KEY_BLOCKED_DEVICES = "blocked_devices_json"

    private val _syncPolicy = MutableStateFlow(SyncPolicy())
    val syncPolicy: StateFlow<SyncPolicy> = _syncPolicy.asStateFlow()

    @Volatile
    private var isInitialized = false
    private var prefs: SharedPreferences? = null

    fun init(context: Context?) {
        if (context == null) return
        synchronized(this) {
            if (isInitialized) return
            try {
                val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs = p

                val autoSync = p.getBoolean(KEY_AUTO_SYNC, true)
                val syncPaused = p.getBoolean(KEY_SYNC_PAUSED, false)
                val scopeName = p.getString(KEY_DEFAULT_SCOPE, SyncScope.AUTO.name) ?: SyncScope.AUTO.name
                val defaultScope = try {
                    SyncScope.valueOf(scopeName)
                } catch (e: Exception) {
                    SyncScope.AUTO
                }

                val blockedJson = p.getString(KEY_BLOCKED_DEVICES, null)
                val blockedSet = mutableSetOf<String>()
                if (!blockedJson.isNullOrBlank()) {
                    val arr = JSONArray(blockedJson)
                    for (i in 0 until arr.length()) {
                        val devId = arr.optString(i)
                        if (devId.isNotBlank()) blockedSet.add(devId)
                    }
                }

                val policy = SyncPolicy(
                    isAutoSyncEnabled = autoSync,
                    isSyncPaused = syncPaused,
                    defaultScope = defaultScope,
                    blockedDeviceIds = blockedSet
                )
                _syncPolicy.value = policy
                isInitialized = true
                Log.i(TAG, "SyncPolicyManager initialized: autoSync=$autoSync, paused=$syncPaused, scope=$defaultScope")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize SyncPolicyManager", e)
            }
        }
    }

    private fun savePolicy(policy: SyncPolicy) {
        _syncPolicy.value = policy
        prefs?.let { p ->
            try {
                val blockedArr = JSONArray()
                policy.blockedDeviceIds.forEach { blockedArr.put(it) }

                p.edit()
                    .putBoolean(KEY_AUTO_SYNC, policy.isAutoSyncEnabled)
                    .putBoolean(KEY_SYNC_PAUSED, policy.isSyncPaused)
                    .putString(KEY_DEFAULT_SCOPE, policy.defaultScope.name)
                    .putString(KEY_BLOCKED_DEVICES, blockedArr.toString())
                    .apply()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist SyncPolicy", e)
            }
        }
    }

    fun toggleAutoSync(): Boolean {
        val current = _syncPolicy.value
        val updated = current.copy(isAutoSyncEnabled = !current.isAutoSyncEnabled)
        savePolicy(updated)
        Log.i(TAG, "Toggled autoSync: ${updated.isAutoSyncEnabled}")
        return updated.isAutoSyncEnabled
    }

    fun togglePauseSync(): Boolean {
        val current = _syncPolicy.value
        val updated = current.copy(isSyncPaused = !current.isSyncPaused)
        savePolicy(updated)
        Log.i(TAG, "Toggled pauseSync: ${updated.isSyncPaused}")
        return updated.isSyncPaused
    }

    fun setAutoSync(enabled: Boolean) {
        val current = _syncPolicy.value
        if (current.isAutoSyncEnabled != enabled) {
            savePolicy(current.copy(isAutoSyncEnabled = enabled))
        }
    }

    fun setSyncPaused(paused: Boolean) {
        val current = _syncPolicy.value
        if (current.isSyncPaused != paused) {
            savePolicy(current.copy(isSyncPaused = paused))
        }
    }

    fun pauseSync() = setSyncPaused(true)
    fun resumeSync() = setSyncPaused(false)

    fun setSyncScope(scope: SyncScope) {
        val current = _syncPolicy.value
        if (current.defaultScope != scope) {
            savePolicy(current.copy(defaultScope = scope))
        }
    }

    fun setDeviceBlocked(deviceId: String, blocked: Boolean) {
        val current = _syncPolicy.value
        val newBlocked = if (blocked) {
            current.blockedDeviceIds + deviceId
        } else {
            current.blockedDeviceIds - deviceId
        }
        savePolicy(current.copy(blockedDeviceIds = newBlocked))
    }

    fun setBlockedDevices(deviceIds: Set<String>) {
        val current = _syncPolicy.value
        savePolicy(current.copy(blockedDeviceIds = deviceIds))
    }

    fun updatePolicy(policy: SyncPolicy) {
        savePolicy(policy)
    }

    fun resetToDefaults() {
        savePolicy(SyncPolicy())
    }

    fun getPolicy(): SyncPolicy = _syncPolicy.value
}
