package com.example.sync.model

import com.example.data.clipboard.ClipboardCoreManager
import com.example.data.model.ClipboardItem
import org.json.JSONObject

/**
 * Serialization and deserialization utilities for transmitting [ClipboardItem] over network transports.
 * Encodes all rich clipboard metadata (type, mimeType, fileName, sizeBytes, SHA-256 hash)
 * safely with standard JSON escaping for Unicode, emoji, multiline, code, JSON, and binary data.
 */
fun parseClipboardItemFromJson(jsonString: String): ClipboardItem? {
    return try {
        val trimmed = jsonString.trim()
        if (!trimmed.startsWith("{")) return null
        val json = JSONObject(trimmed)
        if (json.optString("payloadType") != "CLIPBOARD_ITEM") return null

        val content = json.getString("content")
        val rawHash = json.optString("hash")
        val hash = if (rawHash.isNullOrBlank()) ClipboardCoreManager.computeSha256(content) else rawHash
        val type = json.optString("type", ClipboardItem.TYPE_TEXT)
        val defaultMime = when (type) {
            ClipboardItem.TYPE_HTML -> ClipboardItem.MIME_TEXT_HTML
            ClipboardItem.TYPE_IMAGE -> ClipboardItem.MIME_IMAGE_PNG
            ClipboardItem.TYPE_FILE -> ClipboardItem.MIME_OCTET_STREAM
            else -> ClipboardItem.MIME_TEXT_PLAIN
        }
        val mimeType = json.optString("mimeType", defaultMime)
        val fileName = if (json.has("fileName") && !json.isNull("fileName")) json.getString("fileName") else null
        val sizeBytes = json.optLong("sizeBytes", content.toByteArray(Charsets.UTF_8).size.toLong())

        ClipboardItem(
            id = json.getString("id"),
            sourceDeviceId = json.getString("sourceDeviceId"),
            sourceDeviceName = json.optString("sourceDeviceName", "Remote Device"),
            type = type,
            content = content,
            mimeType = mimeType,
            fileName = fileName,
            sizeBytes = sizeBytes,
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            expiresAt = json.optLong("expiresAt", System.currentTimeMillis() + (7L * 24 * 60 * 60 * 1000)),
            hash = hash,
            isFavorite = json.optBoolean("isFavorite", false),
            isPinned = json.optBoolean("isPinned", false)
        )
    } catch (e: Exception) {
        null
    }
}
