package com.example.core.protocol

import com.example.core.capability.DeviceCapabilities
import com.example.core.capability.PlatformType
import com.example.data.model.ClipboardItem
import com.example.sync.model.parseClipboardItemFromJson
import org.json.JSONObject

/**
 * Protocol Message Type identifier.
 */
enum class ProtocolMessageType {
    HELLO,
    ACK,
    PING,
    PONG,
    DISCONNECT,
    CLIPBOARD_ITEM,
    CAPABILITIES,
    ERROR,
    UNKNOWN
}

/**
 * Universal protocol envelope for cross-platform message framing.
 */
data class ProtocolEnvelope(
    val messageType: ProtocolMessageType,
    val protocolVersion: Int = DeviceCapabilities.CURRENT_PROTOCOL_VERSION,
    val senderDeviceId: String,
    val senderDeviceName: String,
    val senderPlatform: PlatformType = PlatformType.ANDROID,
    val payload: String = "",
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJsonString(): String {
        val json = JSONObject()
        json.put("messageType", messageType.name)
        json.put("protocolVersion", protocolVersion)
        json.put("senderDeviceId", senderDeviceId)
        json.put("senderDeviceName", senderDeviceName)
        json.put("senderPlatform", senderPlatform.name)
        json.put("payload", payload)
        json.put("timestamp", timestamp)
        return json.toString()
    }

    companion object {
        /**
         * Parses a protocol string (either structured ProtocolEnvelope JSON or legacy CLIPBOARD_ITEM JSON / string).
         */
        fun parse(rawMessage: String): ProtocolEnvelope? {
            val trimmed = rawMessage.trim()
            if (!trimmed.startsWith("{")) {
                // Check if it's a legacy HELLO or ACK plaintext frame
                return when {
                    trimmed.startsWith("HELLO") -> parseLegacyHandshake(trimmed, ProtocolMessageType.HELLO)
                    trimmed.startsWith("ACK") -> parseLegacyHandshake(trimmed, ProtocolMessageType.ACK)
                    trimmed.startsWith("PING") -> ProtocolEnvelope(ProtocolMessageType.PING, 1, "", "", PlatformType.OTHER, trimmed)
                    trimmed.startsWith("PONG") -> ProtocolEnvelope(ProtocolMessageType.PONG, 1, "", "", PlatformType.OTHER, trimmed)
                    else -> null
                }
            }

            return try {
                val json = JSONObject(trimmed)
                if (json.has("messageType")) {
                    val typeStr = json.getString("messageType")
                    val type = try {
                        ProtocolMessageType.valueOf(typeStr)
                    } catch (e: Exception) {
                        ProtocolMessageType.UNKNOWN
                    }
                    val platformStr = json.optString("senderPlatform", PlatformType.OTHER.name)
                    val platform = try {
                        PlatformType.valueOf(platformStr)
                    } catch (e: Exception) {
                        PlatformType.OTHER
                    }
                    ProtocolEnvelope(
                        messageType = type,
                        protocolVersion = json.optInt("protocolVersion", 1),
                        senderDeviceId = json.optString("senderDeviceId", ""),
                        senderDeviceName = json.optString("senderDeviceName", ""),
                        senderPlatform = platform,
                        payload = json.optString("payload", ""),
                        timestamp = json.optLong("timestamp", System.currentTimeMillis())
                    )
                } else if (json.optString("payloadType") == "CLIPBOARD_ITEM") {
                    // Backward-compatible direct CLIPBOARD_ITEM frame
                    val item = parseClipboardItemFromJson(trimmed) ?: return null
                    ProtocolEnvelope(
                        messageType = ProtocolMessageType.CLIPBOARD_ITEM,
                        protocolVersion = 1,
                        senderDeviceId = item.sourceDeviceId,
                        senderDeviceName = item.sourceDeviceName,
                        senderPlatform = PlatformType.ANDROID,
                        payload = trimmed,
                        timestamp = item.createdAt
                    )
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }

        private fun parseLegacyHandshake(raw: String, type: ProtocolMessageType): ProtocolEnvelope {
            var deviceId = ""
            var deviceName = ""
            val parts = raw.split(" ")
            if (parts.size > 1) {
                val payloadPart = parts.subList(1, parts.size).joinToString(" ")
                val kvPairs = payloadPart.split(";")
                for (kv in kvPairs) {
                    val eqIdx = kv.indexOf('=')
                    if (eqIdx != -1) {
                        val key = kv.substring(0, eqIdx).trim()
                        val value = kv.substring(eqIdx + 1).trim()
                        if (key == "deviceId") deviceId = value
                        if (key == "deviceName") deviceName = value
                    }
                }
            }
            return ProtocolEnvelope(
                messageType = type,
                protocolVersion = 1,
                senderDeviceId = deviceId,
                senderDeviceName = deviceName,
                senderPlatform = PlatformType.ANDROID,
                payload = raw
            )
        }
    }
}
