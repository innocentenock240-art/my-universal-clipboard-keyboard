package com.example.data.clipboard

import com.example.core.adapter.ClipboardAdapter

/**
 * Abstraction for clipboard capture sources.
 * Decouples system-level clipboard listeners from core business logic.
 * Implements the universal [ClipboardAdapter] contract.
 */
interface ClipboardCaptureSource : ClipboardAdapter {
    /**
     * Start monitoring for clipboard changes.
     */
    fun start()

    /**
     * Stop monitoring for clipboard changes.
     */
    fun stop()

    override fun startCapturing() = start()
    override fun stopCapturing() = stop()

    /**
     * Register callback to be invoked when new clipboard text is detected.
     */
    override fun setOnClipCapturedListener(listener: (String) -> Unit)

    /**
     * Register callback for rich clipboard items (HTML, images, binary files).
     */
    override fun setOnRichClipCapturedListener(listener: (type: String, content: String, mimeType: String, fileName: String?, sizeBytes: Long) -> Unit) {}

    /**
     * Check if capture is currently active.
     */
    override fun isCapturing(): Boolean

    /**
     * Force-check the current clipboard content.
     */
    override fun checkCurrentClip()

    /**
     * Set/update system clipboard content.
     */
    override fun setClipText(text: String)

    /**
     * Set/update system clipboard HTML formatted content.
     */
    override fun setClipHtml(htmlText: String, plainTextFallback: String) {}

    /**
     * Set/update system clipboard rich content (Image / File / etc.).
     */
    override fun setClipRich(type: String, content: String, mimeType: String, fileName: String?) {}
}

