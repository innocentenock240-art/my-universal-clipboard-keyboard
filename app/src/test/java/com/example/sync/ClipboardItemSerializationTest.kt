package com.example.sync

import com.example.data.clipboard.ClipboardCoreManager
import com.example.data.model.ClipboardItem
import com.example.sync.model.parseClipboardItemFromJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ClipboardItemSerializationTest {

    @Test
    fun testSerializationAndDeserializationSuccess() {
        val originalContent = "Hello, end-to-end sync!"
        val hash = ClipboardCoreManager.computeSha256(originalContent)
        val item = ClipboardItem(
            id = "clip_123",
            sourceDeviceId = "phone_a_001",
            sourceDeviceName = "Pixel 8",
            type = "TEXT",
            content = originalContent,
            createdAt = 1700000000000L,
            expiresAt = 1700604000000L,
            hash = hash,
            isFavorite = true,
            isPinned = false
        )

        val jsonStr = item.toJsonString()
        assertNotNull(jsonStr)

        val deserialized = parseClipboardItemFromJson(jsonStr)
        assertNotNull(deserialized)
        assertEquals(item.id, deserialized?.id)
        assertEquals(item.sourceDeviceId, deserialized?.sourceDeviceId)
        assertEquals(item.sourceDeviceName, deserialized?.sourceDeviceName)
        assertEquals(item.type, deserialized?.type)
        assertEquals(item.content, deserialized?.content)
        assertEquals(item.createdAt, deserialized?.createdAt)
        assertEquals(item.expiresAt, deserialized?.expiresAt)
        assertEquals(item.hash, deserialized?.hash)
        assertEquals(item.isFavorite, deserialized?.isFavorite)
        assertEquals(item.isPinned, deserialized?.isPinned)
    }

    @Test
    fun testDeserializationInvalidJsonReturnsNull() {
        val result = parseClipboardItemFromJson("NOT_A_JSON")
        assertNull(result)

        val nonClipboardJson = "{\"payloadType\":\"UNKNOWN_TYPE\",\"id\":\"123\"}"
        val result2 = parseClipboardItemFromJson(nonClipboardJson)
        assertNull(result2)
    }

    @Test
    fun testDeserializationComputesHashIfMissing() {
        val rawJson = """
            {
                "payloadType": "CLIPBOARD_ITEM",
                "id": "clip_456",
                "sourceDeviceId": "phone_b",
                "content": "Sample test text"
            }
        """.trimIndent()

        val item = parseClipboardItemFromJson(rawJson)
        assertNotNull(item)
        assertEquals("clip_456", item?.id)
        assertEquals("Sample test text", item?.content)
        val expectedHash = ClipboardCoreManager.computeSha256("Sample test text")
        assertEquals(expectedHash, item?.hash)
    }

    @Test
    fun testRichContentSerializationAndDeserializationSuccess() {
        val imageBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkWPjfDwAEeQHzE1LqtwAAAABJRU5ErkJggg=="
        val hash = ClipboardCoreManager.computeSha256(imageBase64)
        val richItem = ClipboardItem(
            id = "clip_img_999",
            sourceDeviceId = "macbook_pro_01",
            sourceDeviceName = "MacBook Pro M3",
            type = ClipboardItem.TYPE_IMAGE,
            content = imageBase64,
            mimeType = ClipboardItem.MIME_IMAGE_PNG,
            fileName = "screenshot.png",
            sizeBytes = 72L,
            createdAt = 1700000000000L,
            expiresAt = 1700604000000L,
            hash = hash,
            isFavorite = true,
            isPinned = true
        )

        val jsonStr = richItem.toJsonString()
        assertNotNull(jsonStr)

        val deserialized = parseClipboardItemFromJson(jsonStr)
        assertNotNull(deserialized)
        assertEquals(richItem.id, deserialized?.id)
        assertEquals(richItem.type, deserialized?.type)
        assertEquals(richItem.mimeType, deserialized?.mimeType)
        assertEquals(richItem.fileName, deserialized?.fileName)
        assertEquals(richItem.sizeBytes, deserialized?.sizeBytes)
        assertEquals(richItem.content, deserialized?.content)
        assertEquals(richItem.hash, deserialized?.hash)
    }

    @Test
    fun testUnicodeAndComplexUrlSerialization() {
        val unicodeText = "🚀 Universal Clipboard 🌍: 日本語, Español, 中文, עברית, العربية, 🐍 Code: `fun sync() { return true }`"
        val hash = ClipboardCoreManager.computeSha256(unicodeText)
        val item = ClipboardItem(
            id = "clip_unicode_1",
            sourceDeviceId = "linux_desktop",
            sourceDeviceName = "Ubuntu Workstation",
            type = ClipboardItem.TYPE_TEXT,
            content = unicodeText,
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 600000L,
            hash = hash
        )

        val json = item.toJsonString()
        val parsed = parseClipboardItemFromJson(json)
        assertNotNull(parsed)
        assertEquals(unicodeText, parsed?.content)
        assertEquals(hash, parsed?.hash)
    }
}
