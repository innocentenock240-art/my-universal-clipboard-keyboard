package com.example.data.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.data.model.ClipboardItem
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Android implementation of [ClipboardCaptureSource].
 * Responsible for interacting with Android's [ClipboardManager] and [ClipData].
 * Handles plain text, HTML formatted text, images (URI/bitmap Base64 encoded), and binary items.
 */
class AndroidClipboardCaptureSource(
    private val context: Context
) : ClipboardCaptureSource {

    private val clipboardManager: ClipboardManager? by lazy {
        try {
            context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        } catch (e: Exception) {
            Log.w(TAG, "Failed to retrieve ClipboardManager service", e)
            null
        }
    }

    @Volatile
    private var capturing: Boolean = false

    private var onClipCapturedListener: ((String) -> Unit)? = null
    private var onRichClipCapturedListener: ((type: String, content: String, mimeType: String, fileName: String?, sizeBytes: Long) -> Unit)? = null

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        checkCurrentClip()
    }

    override fun start() {
        if (capturing) return
        val manager = clipboardManager ?: return
        try {
            manager.addPrimaryClipChangedListener(clipListener)
            capturing = true
            Log.d(TAG, "AndroidClipboardCaptureSource listener registered")
            checkCurrentClip()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register clip listener", e)
        }
    }

    override fun stop() {
        if (!capturing) return
        val manager = clipboardManager ?: return
        try {
            manager.removePrimaryClipChangedListener(clipListener)
            capturing = false
            Log.d(TAG, "AndroidClipboardCaptureSource listener unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister clip listener", e)
        }
    }

    override fun setOnClipCapturedListener(listener: (String) -> Unit) {
        this.onClipCapturedListener = listener
    }

    override fun setOnRichClipCapturedListener(listener: (type: String, content: String, mimeType: String, fileName: String?, sizeBytes: Long) -> Unit) {
        this.onRichClipCapturedListener = listener
    }

    override fun isCapturing(): Boolean = capturing

    override fun checkCurrentClip() {
        val manager = clipboardManager ?: return
        try {
            if (!manager.hasPrimaryClip()) return
            val clipData = manager.primaryClip ?: return
            if (clipData.itemCount == 0) return

            val description = clipData.description
            val item = clipData.getItemAt(0)

            // 1. Check for Image / URI MIME types
            val isImageMime = (0 until (description?.mimeTypeCount ?: 0)).any { idx ->
                description?.getMimeType(idx)?.startsWith("image/") == true
            } || description?.hasMimeType("image/*") == true || (item.uri != null && try {
                context.contentResolver.getType(item.uri)?.startsWith("image/") == true
            } catch (_: Exception) { false })

            if (isImageMime && item.uri != null) {
                var imageCaptured = false
                try {
                    val resolvedMime = try {
                        context.contentResolver.getType(item.uri)
                    } catch (_: Exception) { null }
                    val mime = resolvedMime ?: description?.getMimeType(0) ?: ClipboardItem.MIME_IMAGE_PNG
                    val inputStream: InputStream? = try {
                        context.contentResolver.openInputStream(item.uri)
                    } catch (secEx: SecurityException) {
                        Log.w(TAG, "Permission denied opening image URI: ${item.uri}", secEx)
                        null
                    } catch (ioEx: Exception) {
                        Log.w(TAG, "I/O error opening image URI: ${item.uri}", ioEx)
                        null
                    }
                    if (inputStream != null) {
                        inputStream.use { stream ->
                            val buffer = ByteArrayOutputStream()
                            val data = ByteArray(8192)
                            var nRead: Int
                            var totalRead = 0
                            // Cap at 5MB for clipboard payload transmission
                            while (stream.read(data, 0, data.size).also { nRead = it } != -1 && totalRead < 5 * 1024 * 1024) {
                                buffer.write(data, 0, nRead)
                                totalRead += nRead
                            }
                            val bytes = buffer.toByteArray()
                            if (bytes.isNotEmpty()) {
                                val base64Str = Base64.encodeToString(bytes, Base64.NO_WRAP)
                                val fileName = item.uri.lastPathSegment ?: "screenshot_${System.currentTimeMillis()}.png"
                                onRichClipCapturedListener?.invoke(
                                    ClipboardItem.TYPE_IMAGE,
                                    base64Str,
                                    mime,
                                    fileName,
                                    bytes.size.toLong()
                                )
                                imageCaptured = true
                            }
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Could not read image URI data safely: ${e.message}")
                }
                if (imageCaptured) {
                    return
                }
            }

            // 2. Check for HTML formatted text
            val isHtml = description?.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) == true && !item.htmlText.isNullOrBlank()
            if (isHtml) {
                val htmlContent = item.htmlText?.toString() ?: ""
                val textFallback = item.text?.toString() ?: item.coerceToText(context)?.toString() ?: htmlContent
                onRichClipCapturedListener?.invoke(
                    ClipboardItem.TYPE_HTML,
                    htmlContent,
                    ClipboardItem.MIME_TEXT_HTML,
                    null,
                    htmlContent.toByteArray(Charsets.UTF_8).size.toLong()
                )
                return
            }

            // 3. Plain text / URLs / Code snippets
            val text = item.text?.toString() ?: item.coerceToText(context)?.toString()
            if (!text.isNullOrBlank()) {
                onClipCapturedListener?.invoke(text)
            }
        } catch (e: Exception) {
            // Android 10+ background restriction or SecurityException when unaccessible
            Log.w(TAG, "Clipboard access unavailable or restricted: ${e.message}")
        }
    }

    override fun setClipText(text: String) {
        val manager = clipboardManager ?: return
        try {
            val clipData = ClipData.newPlainText("Universal Clipboard", text)
            manager.setPrimaryClip(clipData)
            Log.d(TAG, "Updated Android system primary clip with text successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update Android system clipboard text: ${e.message}")
        }
    }

    override fun setClipHtml(htmlText: String, plainTextFallback: String) {
        val manager = clipboardManager ?: return
        try {
            val clipData = ClipData.newHtmlText("Universal Clipboard HTML", plainTextFallback, htmlText)
            manager.setPrimaryClip(clipData)
            Log.d(TAG, "Updated Android system primary clip with HTML successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update Android system clipboard with HTML: ${e.message}")
        }
    }

    override fun setClipRich(type: String, content: String, mimeType: String, fileName: String?) {
        val manager = clipboardManager ?: return
        try {
            // Provide plain text fallback or rich data representation on system clip
            val clipData = ClipData.newPlainText(fileName ?: "Universal Clipboard Item", content)
            manager.setPrimaryClip(clipData)
            Log.d(TAG, "Updated Android system primary clip for rich type $type")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update Android system clipboard for rich type $type: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "AndroidClipCaptureSrc"
    }
}
