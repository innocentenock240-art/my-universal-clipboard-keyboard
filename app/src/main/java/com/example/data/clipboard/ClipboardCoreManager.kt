package com.example.data.clipboard

import android.util.Log
import com.example.core.identity.DeviceIdentityManager
import com.example.data.model.ClipboardItem
import com.example.data.repository.ClipboardRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * CLIPBOARD CORE
 * Independent business logic component responsible for:
 * - Receiving raw clipboard content
 * - Validating content
 * - Determining content type (TEXT, URL)
 * - Computing SHA-256 hashes
 * - Duplicate detection
 * - Creating ClipboardItem domain objects
 * - Calculating expiration timestamps
 * - Storing through ClipboardRepository
 * - Exposing clipboard history and capture status to UI
 *
 * This component knows nothing about Wi-Fi, Bluetooth, Cloud, Keyboard IME, or remote devices.
 */
class ClipboardCoreManager(
    private val captureSource: ClipboardCaptureSource,
    private val repository: ClipboardRepository,
    private val deviceId: String = DeviceIdentityManager.getLocalDeviceId(),
    private val deviceName: String = DeviceIdentityManager.getLocalDeviceName(),
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    @Volatile
    var onItemProcessedListener: ((ClipboardItem) -> Unit)? = null

    @Volatile
    var lastCapturedHash: String? = null
        private set

    /**
     * Update the last captured hash directly when an item is received from a remote sync peer.
     */
    fun updateLastCapturedHash(hash: String) {
        lastCapturedHash = hash
    }

    private val _isCaptureActive = MutableStateFlow(false)
    val isCaptureActive: StateFlow<Boolean> = _isCaptureActive.asStateFlow()

    init {
        captureSource.setOnClipCapturedListener { rawText ->
            processClipboardText(rawText)
        }
        captureSource.setOnRichClipCapturedListener { type, content, mimeType, fileName, sizeBytes ->
            processRichClipboardItem(type, content, mimeType, fileName, sizeBytes)
        }
    }

    /**
     * Apply a remote clipboard item to the local system clipboard while updating lastCapturedHash to prevent echo loops.
     */
    fun applyRemoteClipboardItem(item: ClipboardItem) {
        if (item.content.isBlank()) return
        val itemHash = item.hash.ifBlank { computeSha256(item.content) }
        lastCapturedHash = itemHash
        when (item.type) {
            ClipboardItem.TYPE_HTML -> captureSource.setClipHtml(item.content)
            ClipboardItem.TYPE_IMAGE, ClipboardItem.TYPE_FILE -> captureSource.setClipRich(item.type, item.content, item.mimeType, item.fileName)
            else -> captureSource.setClipText(item.content)
        }
    }

    /**
     * Start clipboard capture via the attached source.
     */
    fun startCapture() {
        captureSource.start()
        _isCaptureActive.value = captureSource.isCapturing()
    }

    /**
     * Stop clipboard capture via the attached source.
     */
    fun stopCapture() {
        captureSource.stop()
        _isCaptureActive.value = captureSource.isCapturing()
    }

    /**
     * Trigger a check of current clipboard contents via capture source.
     */
    fun checkClipboard() {
        captureSource.checkCurrentClip()
    }

    /**
     * Process rich clipboard content (HTML, image Base64, binary file, code).
     */
    fun processRichClipboardItem(
        type: String,
        content: String,
        mimeType: String = ClipboardItem.MIME_TEXT_PLAIN,
        fileName: String? = null,
        sizeBytes: Long = 0L
    ): ClipboardItem? {
        if (content.isBlank()) return null
        val hash = computeSha256(content)

        if (hash == lastCapturedHash) {
            return null
        }
        lastCapturedHash = hash

        val now = System.currentTimeMillis()
        val actualSizeBytes = if (sizeBytes > 0) sizeBytes else content.toByteArray(Charsets.UTF_8).size.toLong()
        val newItem = ClipboardItem(
            id = "clip_${now}_${(1000..9999).random()}",
            sourceDeviceId = deviceId,
            sourceDeviceName = deviceName,
            type = type,
            content = content,
            mimeType = mimeType,
            fileName = fileName,
            sizeBytes = actualSizeBytes,
            createdAt = now,
            expiresAt = ClipboardRepository.calculateExpirationTime(now, ClipboardRepository.DEFAULT_RETENTION_DAYS),
            hash = hash
        )

        Log.i(
            TAG,
            "Clipboard Core created rich item [ID: ${newItem.id}, Type: ${newItem.type}, MIME: ${newItem.mimeType}, Size: ${newItem.displaySize}, HashPrefix: ${hash.take(8)}]"
        )

        coroutineScope.launch {
            try {
                repository.insertClipboardItem(newItem)
                onItemProcessedListener?.invoke(newItem)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist captured rich clipboard item into repository", e)
            }
        }

        return newItem
    }

    /**
     * Process raw clipboard text string through validation, hashing, duplicate detection, and persistence.
     * Intelligently categorizes content as URL, CODE, HTML, or TEXT.
     * Returns the created [ClipboardItem] if accepted, or null if invalid/duplicate.
     */
    fun processClipboardText(rawText: String?): ClipboardItem? {
        if (rawText.isNullOrBlank()) {
            return null
        }

        val hash = computeSha256(rawText)

        // Duplicate Detection: Skip if the content SHA-256 hash matches the last processed hash
        if (hash == lastCapturedHash) {
            return null
        }

        lastCapturedHash = hash

        val detectedType = detectContentType(rawText)
        val now = System.currentTimeMillis()
        val bytes = rawText.toByteArray(Charsets.UTF_8)
        val newItem = ClipboardItem(
            id = "clip_${now}_${(1000..9999).random()}",
            sourceDeviceId = deviceId,
            sourceDeviceName = deviceName,
            type = detectedType,
            content = rawText,
            mimeType = if (detectedType == ClipboardItem.TYPE_HTML) ClipboardItem.MIME_TEXT_HTML else ClipboardItem.MIME_TEXT_PLAIN,
            sizeBytes = bytes.size.toLong(),
            createdAt = now,
            expiresAt = ClipboardRepository.calculateExpirationTime(now, ClipboardRepository.DEFAULT_RETENTION_DAYS),
            hash = hash
        )

        // SECURITY REQUIREMENT: Never log the actual clipboard text to Logcat.
        // Log ONLY safe metadata: ID, type, content length, and hash prefix.
        Log.i(
            TAG,
            "Clipboard Core created item [ID: ${newItem.id}, Type: ${newItem.type}, Length: ${rawText.length}, HashPrefix: ${hash.take(8)}]"
        )

        coroutineScope.launch {
            try {
                repository.insertClipboardItem(newItem)
                onItemProcessedListener?.invoke(newItem)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist captured clipboard item into repository", e)
            }
        }

        return newItem
    }

    /**
     * Persists an inbound synchronized item from a remote peer into the local repository.
     * MANDATE: CAPTURE != SYNCHRONIZE. Inbound sync MUST NOT overwrite OS system clipboard.
     */
    suspend fun saveInboundRemoteItem(item: ClipboardItem): Boolean {
        return try {
            repository.insertClipboardItem(item)
            Log.i(TAG, "Inbound remote clipboard item [ID: ${item.id}, Type: ${item.type}] persisted to repository.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist inbound remote clipboard item ${item.id}", e)
            false
        }
    }

    /**
     * Determines whether text is a URL, code snippet, HTML, or plain text.
     */
    private fun detectContentType(text: String): String {
        val trimmed = text.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || 
            trimmed.startsWith("https://", ignoreCase = true) ||
            (trimmed.startsWith("www.", ignoreCase = true) && trimmed.contains("."))) {
            return ClipboardItem.TYPE_URL
        }
        if (trimmed.startsWith("<!DOCTYPE html", ignoreCase = true) ||
            trimmed.startsWith("<html", ignoreCase = true) ||
            (trimmed.startsWith("<div", ignoreCase = true) && trimmed.endsWith("</div>", ignoreCase = true))) {
            return ClipboardItem.TYPE_HTML
        }
        if (isCodeSnippet(text)) {
            return ClipboardItem.TYPE_CODE
        }
        return ClipboardItem.TYPE_TEXT
    }

    private fun isCodeSnippet(text: String): Boolean {
        val codeKeywords = listOf(
            "fun ", "val ", "var ", "class ", "interface ", "function ", "const ", "let ",
            "def ", "import ", "public static void", "System.out.print", "console.log",
            "<?php", "#!/bin/", "SELECT ", "CREATE TABLE", "{\n", "}\n", "=>", "===",
            "package ", "struct ", "impl ", "fn "
        )
        return codeKeywords.any { text.contains(it) } || 
               (text.contains("{") && text.contains("}") && text.lines().size > 2) ||
               (text.trim().startsWith("{") && text.trim().endsWith("}") && text.contains("\":"))
    }

    companion object {
        private const val TAG = "ClipboardCoreManager"

        @Volatile
        private var INSTANCE: ClipboardCoreManager? = null

        fun getInstance(
            context: android.content.Context,
            repository: ClipboardRepository
        ): ClipboardCoreManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val appContext = context.applicationContext
                    val captureSource = AndroidClipboardCaptureSource(appContext)
                    val deviceId = DeviceIdentityManager.getLocalDeviceId(appContext)
                    val deviceName = DeviceIdentityManager.getLocalDeviceName()

                    val instance = ClipboardCoreManager(
                        captureSource = captureSource,
                        repository = repository,
                        deviceId = deviceId,
                        deviceName = deviceName,
                        coroutineScope = CoroutineScope(Dispatchers.Default)
                    )
                    instance.startCapture()
                    INSTANCE = instance
                    instance
                }
            }
        }

        /**
         * Deterministic SHA-256 hash generation for duplicate check and future item identity checks.
         */
        fun computeSha256(input: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
