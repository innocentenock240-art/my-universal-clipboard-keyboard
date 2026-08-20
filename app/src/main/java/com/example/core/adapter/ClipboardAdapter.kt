package com.example.core.adapter

/**
 * Universal interface for platform-specific clipboard capture and injection.
 * Implemented by platform adapters (Android, Windows, macOS, Linux, iOS).
 */
interface ClipboardAdapter {
    fun startCapturing()
    fun stopCapturing()
    fun isCapturing(): Boolean
    fun checkCurrentClip()
    fun setClipText(text: String)
    fun setClipHtml(htmlText: String, plainTextFallback: String = htmlText)
    fun setClipRich(type: String, content: String, mimeType: String, fileName: String? = null)

    fun setOnClipCapturedListener(listener: (String) -> Unit)
    fun setOnRichClipCapturedListener(listener: (type: String, content: String, mimeType: String, fileName: String?, sizeBytes: Long) -> Unit)
}
