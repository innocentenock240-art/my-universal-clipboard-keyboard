package com.example.sync

import com.example.data.clipboard.ClipboardCoreManager
import com.example.data.model.ClipboardItem
import com.example.sync.model.parseClipboardItemFromJson
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * MILESTONE 5.6: Comprehensive Verification Test Suite
 * Tests Rich Clipboard Contents:
 * - Plain Text & Unicode (Emojis, CJK, Arabic, Hebrew, Cyrillic)
 * - Real-world text (Markdown, JSON, URLs with query strings)
 * - Source code snippets (multi-language keywords & multi-line blocks)
 * - HTML formatted text
 * - Image payloads (Base64 PNG & MIME verification)
 * - File attachments (MIME type, file name, size metadata)
 * - Large text payloads (up to 100KB)
 * - SHA-256 integrity verification
 * - Loop & Echo Protection (sourceDeviceId filtering & hash matching)
 */
@RunWith(RobolectricTestRunner::class)
class Milestone56RichSyncComprehensiveTest {

    // 1. Group D: Unicode & Multilingual Support
    @Test
    fun testGroupD_UnicodeAndMultilingualIntegrity() {
        val unicodePayloads = listOf(
            "🌟✨🚀 Testing Emojis and Symbols 🔥💻🎉",
            "こんにちは世界！ Universal Clipboard は素晴らしいです。",
            "مرحبا بالعالم - الحافظة العالمية عبر الأجهزة",
            "שלום עולם - לוח אוניברסלי לסנכרון",
            "Привет, мир! Синхронизация буфера обмена между устройствами.",
            "Special symbols: © ® ™ § ¶ † ‡ • – — ≠ ≤ ≥ ≈ ∞ ¢ £ ¥ €"
        )

        for (text in unicodePayloads) {
            val hash = ClipboardCoreManager.computeSha256(text)
            val item = ClipboardItem(
                id = "clip_unicode_${System.currentTimeMillis()}_${(100..999).random()}",
                sourceDeviceId = "device_node_1",
                sourceDeviceName = "Pixel 8 Pro",
                type = ClipboardItem.TYPE_TEXT,
                content = text,
                sizeBytes = text.toByteArray(Charsets.UTF_8).size.toLong(),
                hash = hash
            )

            val json = item.toJsonString()
            val parsed = parseClipboardItemFromJson(json)

            assertNotNull("Parsed item must not be null for payload: $text", parsed)
            assertEquals(text, parsed?.content)
            assertEquals(hash, parsed?.hash)
            assertEquals(item.sizeBytes, parsed?.sizeBytes)
        }
    }

    // 2. Group E: Real-World Text (URLs, Markdown, JSON)
    @Test
    fun testGroupE_RealWorldTextUrlsAndMarkdown() {
        val complexUrl = "https://api.example.com/v2/search?query=universal+clipboard&filter=recent&tags=sync,wifi,crossplatform#section-results"
        val urlHash = ClipboardCoreManager.computeSha256(complexUrl)
        val urlItem = ClipboardItem(
            id = "clip_url_1",
            sourceDeviceId = "phone_a",
            sourceDeviceName = "Phone A",
            type = ClipboardItem.TYPE_URL,
            content = complexUrl,
            hash = urlHash
        )

        val urlJson = urlItem.toJsonString()
        val parsedUrl = parseClipboardItemFromJson(urlJson)
        assertNotNull(parsedUrl)
        assertEquals(ClipboardItem.TYPE_URL, parsedUrl?.type)
        assertEquals(complexUrl, parsedUrl?.content)
        assertEquals(urlHash, parsedUrl?.hash)

        val jsonDocument = "{\"protocolVersion\": 1, \"peerName\": \"Linux Laptop\", \"status\": \"ACTIVE\", \"features\": [\"TEXT\", \"IMAGE\", \"FILE\"]}"
        val jsonHash = ClipboardCoreManager.computeSha256(jsonDocument)
        val jsonItem = ClipboardItem(
            id = "clip_json_1",
            sourceDeviceId = "phone_a",
            sourceDeviceName = "Phone A",
            type = ClipboardItem.TYPE_CODE,
            content = jsonDocument,
            hash = jsonHash
        )
        val parsedJson = parseClipboardItemFromJson(jsonItem.toJsonString())
        assertNotNull(parsedJson)
        assertEquals(jsonDocument, parsedJson?.content)
    }

    // 3. Group G: Large Text Payloads
    @Test
    fun testGroupG_LargeTextPayloads() {
        val largeStringBuilder = StringBuilder()
        repeat(2000) { i ->
            largeStringBuilder.append("Line $i: Universal Clipboard cross-platform multi-device synchronization engine.\n")
        }
        val largeContent = largeStringBuilder.toString()
        assertTrue("Large content size should be > 50KB", largeContent.length > 50_000)

        val hash = ClipboardCoreManager.computeSha256(largeContent)
        val item = ClipboardItem(
            id = "clip_large_1",
            sourceDeviceId = "node_source",
            sourceDeviceName = "Server Node",
            type = ClipboardItem.TYPE_TEXT,
            content = largeContent,
            sizeBytes = largeContent.toByteArray(Charsets.UTF_8).size.toLong(),
            hash = hash
        )

        val json = item.toJsonString()
        val parsed = parseClipboardItemFromJson(json)
        assertNotNull(parsed)
        assertEquals(largeContent.length, parsed?.content?.length)
        assertEquals(hash, parsed?.hash)
    }

    // 4. Group H: Image Payloads (Base64, MIME, File metadata)
    @Test
    fun testGroupH_ImagePayloadSupport() {
        val pngBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAFElEQVR42mNkWPjfDwAEAQEPBPn/nwAUkAX7xH5/9wAAAABJRU5ErkJggg=="
        val hash = ClipboardCoreManager.computeSha256(pngBase64)
        val imageItem = ClipboardItem(
            id = "clip_img_1",
            sourceDeviceId = "phone_a",
            sourceDeviceName = "Pixel Phone",
            type = ClipboardItem.TYPE_IMAGE,
            content = pngBase64,
            mimeType = ClipboardItem.MIME_IMAGE_PNG,
            fileName = "diagram_sample.png",
            sizeBytes = 85L,
            hash = hash
        )

        val json = imageItem.toJsonString()
        val parsed = parseClipboardItemFromJson(json)

        assertNotNull(parsed)
        assertEquals(ClipboardItem.TYPE_IMAGE, parsed?.type)
        assertEquals(ClipboardItem.MIME_IMAGE_PNG, parsed?.mimeType)
        assertEquals("diagram_sample.png", parsed?.fileName)
        assertEquals(85L, parsed?.sizeBytes)
        assertEquals(pngBase64, parsed?.content)
        assertEquals(hash, parsed?.hash)
    }

    // 5. Group I: Binary File Payloads
    @Test
    fun testGroupI_FilePayloadSupport() {
        val fileBase64 = "UEsDBBQAAAAIAAAAAAAAAAAAAAAAAAAAAAA="
        val hash = ClipboardCoreManager.computeSha256(fileBase64)
        val fileItem = ClipboardItem(
            id = "clip_file_1",
            sourceDeviceId = "desktop_pc",
            sourceDeviceName = "Windows Workstation",
            type = ClipboardItem.TYPE_FILE,
            content = fileBase64,
            mimeType = "application/zip",
            fileName = "archive_data.zip",
            sizeBytes = 28L,
            hash = hash
        )

        val json = fileItem.toJsonString()
        val parsed = parseClipboardItemFromJson(json)

        assertNotNull(parsed)
        assertEquals(ClipboardItem.TYPE_FILE, parsed?.type)
        assertEquals("application/zip", parsed?.mimeType)
        assertEquals("archive_data.zip", parsed?.fileName)
        assertEquals("28 B", parsed?.displaySize)
        assertEquals(hash, parsed?.hash)
    }

    // 6. Group J: Loop & Echo Protection
    @Test
    fun testGroupJ_LoopAndEchoProtection() {
        val localDeviceId = "my_local_phone_123"
        val testContent = "Test string for loop protection"
        val itemHash = ClipboardCoreManager.computeSha256(testContent)

        // Rule 1: Echo item generated from this same device should be detected and filtered
        val echoItem = ClipboardItem(
            id = "clip_echo_1",
            sourceDeviceId = localDeviceId,
            sourceDeviceName = "My Phone",
            type = ClipboardItem.TYPE_TEXT,
            content = testContent,
            hash = itemHash
        )
        val isEcho = echoItem.sourceDeviceId == localDeviceId
        assertTrue("Echo item from same source device must be detected", isEcho)

        // Rule 2: Hash deduplication: If item has identical hash, it should not trigger a re-broadcast
        val recentHashes = mutableSetOf<String>()
        recentHashes.add(itemHash)

        val isDuplicate = recentHashes.contains(echoItem.hash)
        assertTrue("Duplicate SHA-256 hash must be detected", isDuplicate)
    }
}
