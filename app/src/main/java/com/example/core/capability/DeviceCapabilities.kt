package com.example.core.capability

/**
 * Universal Platform Type enumeration representing client operating systems.
 */
enum class PlatformType(val displayName: String) {
    ANDROID("Android"),
    WINDOWS("Windows"),
    MACOS("macOS"),
    LINUX("Linux"),
    IOS("iOS"),
    IPADOS("iPadOS"),
    CHROMEOS("ChromeOS"),
    BSD("BSD"),
    OTHER("Other")
}

/**
 * Platform & Device capability descriptor.
 * Communicated during discovery / connection handshakes to establish mutual capabilities
 * without assuming all platforms support identical clipboard operations.
 */
data class DeviceCapabilities(
    val supportsText: Boolean = true,
    val supportsHtml: Boolean = true,
    val supportsImages: Boolean = true,
    val supportsFiles: Boolean = true,
    val supportsUrls: Boolean = true,
    val supportsCode: Boolean = true,
    val maxPayloadBytes: Long = 5 * 1024 * 1024L, // 5MB default safety limit
    val supportsBackgroundCapture: Boolean = false,
    val supportsBackgroundSync: Boolean = true,
    val protocolVersion: Int = CURRENT_PROTOCOL_VERSION
) {
    fun toJsonString(): String {
        val json = org.json.JSONObject()
        json.put("supportsText", supportsText)
        json.put("supportsHtml", supportsHtml)
        json.put("supportsImages", supportsImages)
        json.put("supportsFiles", supportsFiles)
        json.put("supportsUrls", supportsUrls)
        json.put("supportsCode", supportsCode)
        json.put("maxPayloadBytes", maxPayloadBytes)
        json.put("supportsBackgroundCapture", supportsBackgroundCapture)
        json.put("supportsBackgroundSync", supportsBackgroundSync)
        json.put("protocolVersion", protocolVersion)
        return json.toString()
    }

    companion object {
        const val CURRENT_PROTOCOL_VERSION = 1

        fun fromJsonString(jsonString: String): DeviceCapabilities {
            val json = org.json.JSONObject(jsonString.trim())
            return DeviceCapabilities(
                supportsText = json.optBoolean("supportsText", true),
                supportsHtml = json.optBoolean("supportsHtml", true),
                supportsImages = json.optBoolean("supportsImages", true),
                supportsFiles = json.optBoolean("supportsFiles", true),
                supportsUrls = json.optBoolean("supportsUrls", true),
                supportsCode = json.optBoolean("supportsCode", true),
                maxPayloadBytes = json.optLong("maxPayloadBytes", 5 * 1024 * 1024L),
                supportsBackgroundCapture = json.optBoolean("supportsBackgroundCapture", false),
                supportsBackgroundSync = json.optBoolean("supportsBackgroundSync", true),
                protocolVersion = json.optInt("protocolVersion", CURRENT_PROTOCOL_VERSION)
            )
        }

        val ANDROID_DEFAULT = DeviceCapabilities(
            supportsText = true,
            supportsHtml = true,
            supportsImages = true,
            supportsFiles = true,
            supportsUrls = true,
            supportsCode = true,
            maxPayloadBytes = 5 * 1024 * 1024L,
            supportsBackgroundCapture = false, // Foreground / Companion only per Android 10+
            supportsBackgroundSync = true,
            protocolVersion = CURRENT_PROTOCOL_VERSION
        )

        val DESKTOP_DEFAULT = DeviceCapabilities(
            supportsText = true,
            supportsHtml = true,
            supportsImages = true,
            supportsFiles = true,
            supportsUrls = true,
            supportsCode = true,
            maxPayloadBytes = 25 * 1024 * 1024L,
            supportsBackgroundCapture = true,
            supportsBackgroundSync = true,
            protocolVersion = CURRENT_PROTOCOL_VERSION
        )

        val IOS_DEFAULT = DeviceCapabilities(
            supportsText = true,
            supportsHtml = true,
            supportsImages = true,
            supportsFiles = true,
            supportsUrls = true,
            supportsCode = true,
            maxPayloadBytes = 5 * 1024 * 1024L,
            supportsBackgroundCapture = false, // Restricted by iOS Pasteboard privacy
            supportsBackgroundSync = false,
            protocolVersion = CURRENT_PROTOCOL_VERSION
        )
    }
}
