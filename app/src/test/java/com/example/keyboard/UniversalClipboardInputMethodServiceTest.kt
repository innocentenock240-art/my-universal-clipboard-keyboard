package com.example.keyboard

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.core.content.SmartContentClassifier
import com.example.core.transport.TransportManager
import com.example.core.transport.TransportType
import com.example.data.model.ClipboardItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UniversalClipboardInputMethodServiceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testImeServiceCanBeInstantiated() {
        val controller = Robolectric.buildService(UniversalClipboardInputMethodService::class.java)
        val service = controller.create().get()
        assertNotNull(service)
    }

    @Test
    fun testManifestRegistrationIsCorrect() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageManager = context.packageManager
        val componentName = ComponentName(context, UniversalClipboardInputMethodService::class.java)

        val serviceInfo = packageManager.getServiceInfo(componentName, PackageManager.GET_META_DATA)
        assertNotNull(serviceInfo)
        assertEquals("android.permission.BIND_INPUT_METHOD", serviceInfo.permission)
    }

    @Test
    fun testKeyboardViewCanBeCreated() {
        val controller = Robolectric.buildService(UniversalClipboardInputMethodService::class.java)
        val service = controller.create().get()

        val inputView = service.onCreateInputView()
        assertNotNull(inputView)
    }

    @Test
    fun testDirectTextInsertionViaInputConnection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val editText = EditText(context)
        val editorInfo = EditorInfo()
        val realInputConnection = editText.onCreateInputConnection(editorInfo)

        val service = object : UniversalClipboardInputMethodService() {
            override fun getCurrentInputConnection(): InputConnection? {
                return realInputConnection
            }
        }

        // Verify service insertText delegates to InputConnection
        service.insertText("Hello Universal Clipboard")
        assertEquals("Hello Universal Clipboard", editText.text.toString())

        // Verify service handleBackspace delegates to InputConnection
        service.handleBackspace()
        assertEquals("Hello Universal Clipboar", editText.text.toString())
    }

    @Test
    fun testKeyboardUiAndClipboardPanelInteraction() {
        var insertedText = ""
        val sampleItems = listOf(
            ClipboardItem(
                id = "item_1",
                sourceDeviceId = "dev_local",
                sourceDeviceName = "Local Phone",
                type = "TEXT",
                content = "Copied secret code 1234",
                createdAt = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + 600000
            )
        )

        composeTestRule.setContent {
            KeyboardScreen(
                clipboardItems = sampleItems,
                onInsertText = { insertedText += it },
                onBackspace = {},
                onEnter = {}
            )
        }

        // Verify keyboard screen is rendered
        composeTestRule.onNodeWithTag("universal_keyboard_screen").assertIsDisplayed()

        // Click letter 'a' key
        composeTestRule.onNodeWithTag("key_a").performClick()
        assertEquals("a", insertedText)

        // Toggle to Clipboard History mode
        composeTestRule.onNodeWithTag("toggle_clipboard_btn").performClick()
        composeTestRule.onNodeWithTag("clipboard_panel").assertIsDisplayed()

        // Select item from clipboard panel
        composeTestRule.onNodeWithTag("clip_item_item_1").performClick()
        assertEquals("aCopied secret code 1234", insertedText)
    }

    @Test
    fun testEmptyClipboardPanelDisplaysEmptyMessage() {
        composeTestRule.setContent {
            KeyboardScreen(
                clipboardItems = emptyList(),
                onInsertText = {},
                onBackspace = {},
                onEnter = {}
            )
        }

        // Toggle to Clipboard History mode
        composeTestRule.onNodeWithTag("toggle_clipboard_btn").performClick()
        composeTestRule.onNodeWithText("Clipboard history is empty").assertIsDisplayed()
    }

    @Test
    fun testBackspaceDeletesSelectedTextWhenTextIsSelected() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val editText = EditText(context)
        editText.setText("Hello World")
        editText.setSelection(0, 5) // Select "Hello"

        val editorInfo = EditorInfo()
        val realInputConnection = editText.onCreateInputConnection(editorInfo)

        val service = object : UniversalClipboardInputMethodService() {
            override fun getCurrentInputConnection(): InputConnection? {
                return realInputConnection
            }
        }

        service.handleBackspace()
        // "Hello" selected, so backspace replaces selection with empty string -> " World"
        assertEquals(" World", editText.text.toString())
    }

    @Test
    fun testMultilingualAndUnicodeDatasetClassification() {
        // Arabic
        val arabic = "مرحبا بالعالم"
        val arabicItem = ClipboardItem(id = "ar", sourceDeviceId = "dev_1", content = arabic)
        assertEquals(SmartContentClassifier.ContentCategory.TEXT, SmartContentClassifier.classify(arabicItem))

        // Hebrew
        val hebrew = "שלום עולם"
        val hebrewItem = ClipboardItem(id = "he", sourceDeviceId = "dev_1", content = hebrew)
        assertEquals(SmartContentClassifier.ContentCategory.TEXT, SmartContentClassifier.classify(hebrewItem))

        // Japanese
        val japanese = "こんにちは世界"
        val jaItem = ClipboardItem(id = "ja", sourceDeviceId = "dev_1", content = japanese)
        assertEquals(SmartContentClassifier.ContentCategory.TEXT, SmartContentClassifier.classify(jaItem))

        // Chinese
        val chinese = "你好世界"
        val zhItem = ClipboardItem(id = "zh", sourceDeviceId = "dev_1", content = chinese)
        assertEquals(SmartContentClassifier.ContentCategory.TEXT, SmartContentClassifier.classify(zhItem))

        // Cyrillic
        val cyrillic = "Привет, мир!"
        val ruItem = ClipboardItem(id = "ru", sourceDeviceId = "dev_1", content = cyrillic)
        assertEquals(SmartContentClassifier.ContentCategory.TEXT, SmartContentClassifier.classify(ruItem))

        // Accented Latin
        val accented = "Café & Naïve résumé crème brûlée"
        val accItem = ClipboardItem(id = "acc", sourceDeviceId = "dev_1", content = accented)
        assertEquals(SmartContentClassifier.ContentCategory.TEXT, SmartContentClassifier.classify(accItem))

        // Emoji & Combined Emoji
        val emoji = "👨‍👩‍👧‍👦 🚀 📋 💻"
        val emojiItem = ClipboardItem(id = "em", sourceDeviceId = "dev_1", content = emoji)
        assertEquals(SmartContentClassifier.ContentCategory.TEXT, SmartContentClassifier.classify(emojiItem))
    }

    @Test
    fun testStructuredContentAndSensitiveDataDetection() {
        // URL
        val urlItem = ClipboardItem(id = "u1", sourceDeviceId = "dev_1", content = "https://ai.google.dev")
        assertEquals(SmartContentClassifier.ContentCategory.URL, SmartContentClassifier.classify(urlItem))

        // Email
        val emailItem = ClipboardItem(id = "e1", sourceDeviceId = "dev_1", content = "support@example.com")
        assertEquals(SmartContentClassifier.ContentCategory.EMAIL, SmartContentClassifier.classify(emailItem))

        // JSON
        val jsonItem = ClipboardItem(id = "j1", sourceDeviceId = "dev_1", content = "{\"name\":\"Universal Clipboard\",\"version\":5.9}")
        assertEquals(SmartContentClassifier.ContentCategory.JSON, SmartContentClassifier.classify(jsonItem))

        // Code
        val codeItem = ClipboardItem(id = "c1", sourceDeviceId = "dev_1", content = "fun calculateChecksum(input: String): Long {\n  return input.hashCode().toLong()\n}")
        assertEquals(SmartContentClassifier.ContentCategory.CODE, SmartContentClassifier.classify(codeItem))

        // Sensitive Tokens / Passwords
        val tokenItem = ClipboardItem(id = "s1", sourceDeviceId = "dev_1", content = "ghp_1234567890abcdefghijklmnopqrstuvwx")
        assertTrue(SmartContentClassifier.isSensitive(tokenItem.content))
        assertEquals(SmartContentClassifier.ContentCategory.SENSITIVE, SmartContentClassifier.classify(tokenItem))

        val passwordItem = ClipboardItem(id = "s2", sourceDeviceId = "dev_1", content = "password=SuperSecretP@ssw0rd123!")
        assertTrue(SmartContentClassifier.isSensitive(passwordItem.content))
    }
}
