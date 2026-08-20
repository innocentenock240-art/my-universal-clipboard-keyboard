package com.example.core.identity

import android.content.Context
import com.example.core.capability.DeviceCapabilities
import com.example.core.capability.PlatformType
import java.util.UUID

/**
 * Universal, language-agnostic representation of a device's identity within the Universal Clipboard ecosystem.
 * Stable across IP changes, network migrations, and application restarts.
 */
data class DeviceIdentity(
    val deviceId: String,
    val deviceName: String,
    val platformType: PlatformType = PlatformType.ANDROID,
    val platformVersion: String = "",
    val capabilities: DeviceCapabilities = DeviceCapabilities.ANDROID_DEFAULT
)

object DeviceIdentityManager {
    private const val PREFS_NAME = "uclip_device_prefs"
    private const val KEY_DEVICE_ID = "local_device_id"
    private const val KEY_DEVICE_NAME = "local_device_name"

    @Volatile
    private var cachedDeviceId: String? = null

    @Volatile
    private var cachedDeviceName: String? = null

    /**
     * Obtains the authoritative persistent local device ID.
     * 1. If [customId] is explicitly provided (e.g. in test fixtures), it is used.
     * 2. If already loaded/cached in-memory, returns the cached authoritative ID.
     * 3. If a [context] is provided, checks persistent SharedPreferences (`local_device_id`).
     *    - If an existing ID is found (including legacy IDs), it is preserved and returned.
     *    - If no ID exists, generates a persistent UUID-based identifier (`uclip_dev_<uuid>`), saves to prefs, and returns.
     * 4. If no context is available and no cached ID exists, generates a stable in-memory UUID-based ID and caches it.
     */
    fun getLocalDeviceId(context: Context? = null, customId: String? = null): String {
        if (!customId.isNullOrBlank()) {
            return customId
        }
        cachedDeviceId?.let { return it }

        synchronized(this) {
            cachedDeviceId?.let { return it }

            val deviceId = context?.let { ctx ->
                try {
                    val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    var storedId = prefs.getString(KEY_DEVICE_ID, null)
                    if (storedId.isNullOrBlank()) {
                        // Generate a unique, persistent device ID independent of hardware/model
                        val randomHex = UUID.randomUUID().toString().replace("-", "").take(16)
                        storedId = "uclip_dev_$randomHex"
                        prefs.edit().putString(KEY_DEVICE_ID, storedId).apply()
                    }
                    storedId
                } catch (e: Throwable) {
                    "uclip_dev_" + UUID.randomUUID().toString().replace("-", "").take(16)
                }
            } ?: ("uclip_dev_" + UUID.randomUUID().toString().replace("-", "").take(16))

            cachedDeviceId = deviceId
            return deviceId
        }
    }

    /**
     * Obtains the local device's friendly display name.
     * Can be customized or defaults to the device model / "Android Device".
     */
    fun getLocalDeviceName(context: Context? = null): String {
        cachedDeviceName?.let { return it }

        val name = context?.let { ctx ->
            try {
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.getString(KEY_DEVICE_NAME, null)
            } catch (e: Throwable) {
                null
            }
        } ?: run {
            val rawModel = try { android.os.Build.MODEL } catch (e: Throwable) { "Android Device" }
            if (rawModel.isNullOrBlank()) "Android Device" else rawModel
        }

        cachedDeviceName = name
        return name
    }

    fun setLocalDeviceName(context: Context?, name: String) {
        if (name.isBlank()) return
        cachedDeviceName = name
        context?.let { ctx ->
            try {
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putString(KEY_DEVICE_NAME, name).apply()
            } catch (e: Throwable) {
                // Ignore storage failures
            }
        }
    }

    fun getLocalIdentity(context: Context? = null, customId: String? = null): DeviceIdentity {
        return DeviceIdentity(
            deviceId = getLocalDeviceId(context, customId),
            deviceName = getLocalDeviceName(context),
            platformType = PlatformType.ANDROID,
            platformVersion = try { android.os.Build.VERSION.RELEASE ?: "" } catch (e: Throwable) { "" },
            capabilities = DeviceCapabilities.ANDROID_DEFAULT
        )
    }

    fun getLocalDevice(context: Context? = null, customId: String? = null): com.example.data.model.Device {
        val identity = getLocalIdentity(context, customId)
        return com.example.data.model.Device(
            deviceId = identity.deviceId,
            deviceName = identity.deviceName,
            deviceType = "PHONE",
            isLocalDevice = true,
            isOnline = false,
            isPaired = true,
            connectionState = com.example.data.model.ConnectionState.DISCONNECTED,
            platform = identity.platformType,
            capabilities = identity.capabilities
        )
    }

    /**
     * For testing only: resets internal in-memory cache to simulate app restart or context changes.
     */
    fun resetCacheForTesting(newCachedId: String? = null) {
        synchronized(this) {
            cachedDeviceId = newCachedId
            cachedDeviceName = null
        }
    }
}

