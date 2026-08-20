package com.example.data.model

/**
 * Data class representing a synchronized clipboard item.
 * Platform-neutral domain representation supporting plain text, rich formatted text,
 * URLs, code snippets, images, and binary files.
 */
data class ClipboardItem(
    val id: String,
    val sourceDeviceId: String,
    val sourceDeviceName: String = "Local Device",
    val type: String = TYPE_TEXT, // TEXT, URL, CODE, HTML, IMAGE, FILE
    val content: String, // Text content or Base64 encoded payload for binary data
    val mimeType: String = MIME_TEXT_PLAIN,
    val fileName: String? = null,
    val sizeBytes: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + (7L * 24 * 60 * 60 * 1000), // Default 7-day retention
    val hash: String = "",
    val isFavorite: Boolean = false,
    val isPinned: Boolean = false
) {
    val displaySize: String
        get() {
            val bytes = if (sizeBytes > 0) sizeBytes else content.toByteArray(Charsets.UTF_8).size.toLong()
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
                else -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
            }
        }

    companion object {
        const val TYPE_TEXT = "TEXT"
        const val TYPE_URL = "URL"
        const val TYPE_CODE = "CODE"
        const val TYPE_HTML = "HTML"
        const val TYPE_IMAGE = "IMAGE"
        const val TYPE_FILE = "FILE"

        const val MIME_TEXT_PLAIN = "text/plain"
        const val MIME_TEXT_HTML = "text/html"
        const val MIME_IMAGE_PNG = "image/png"
        const val MIME_IMAGE_JPEG = "image/jpeg"
        const val MIME_IMAGE_WEBP = "image/webp"
        const val MIME_APPLICATION_PDF = "application/pdf"
        const val MIME_OCTET_STREAM = "application/octet-stream"

        fun fromJsonString(jsonString: String): ClipboardItem? =
            com.example.sync.model.parseClipboardItemFromJson(jsonString)
    }

    fun toJsonString(): String {
        val json = org.json.JSONObject()
        json.put("payloadType", "CLIPBOARD_ITEM")
        json.put("id", id)
        json.put("sourceDeviceId", sourceDeviceId)
        json.put("sourceDeviceName", sourceDeviceName)
        json.put("type", type)
        json.put("content", content)
        json.put("mimeType", mimeType)
        if (fileName != null) {
            json.put("fileName", fileName)
        }
        json.put("sizeBytes", if (sizeBytes > 0) sizeBytes else content.toByteArray(Charsets.UTF_8).size.toLong())
        json.put("createdAt", createdAt)
        json.put("expiresAt", expiresAt)
        json.put("hash", hash)
        json.put("isFavorite", isFavorite)
        json.put("isPinned", isPinned)
        return json.toString()
    }
}
