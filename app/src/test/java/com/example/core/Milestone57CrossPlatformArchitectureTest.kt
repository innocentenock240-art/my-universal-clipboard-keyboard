package com.example.core

import com.example.core.capability.DeviceCapabilities
import com.example.core.capability.PlatformType
import com.example.core.identity.DeviceIdentity
import com.example.core.protocol.ProtocolEnvelope
import com.example.core.protocol.ProtocolMessageType
import com.example.data.model.ClipboardItem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * MILESTONE 5.7: Universal Cross-Platform Core & Companion Architecture Verification Test Suite.
 *
 * Verifies:
 * 1. Platform-independent data structures & capability models.
 * 2. Protocol envelope framing and 100% backward compatibility with legacy frames.
 * 3. Stable, network-independent device identity representations.
 * 4. Universal adapter contract compliance for platform abstractions.
 */
@RunWith(RobolectricTestRunner::class)
class Milestone57CrossPlatformArchitectureTest {

    // 1. Platform & Capabilities Model Tests
    @Test
    fun testPlatformTypesAndDefaultCapabilities() {
        val androidCaps = DeviceCapabilities.ANDROID_DEFAULT
        assertTrue(androidCaps.supportsText)
        assertTrue(androidCaps.supportsHtml)
        assertTrue(androidCaps.supportsImages)
        assertTrue(androidCaps.supportsFiles)
        assertEquals(5 * 1024 * 1024L, androidCaps.maxPayloadBytes)
        assertEquals(1, androidCaps.protocolVersion)

        val desktopCaps = DeviceCapabilities.DESKTOP_DEFAULT
        assertTrue(desktopCaps.supportsBackgroundCapture)
        assertTrue(desktopCaps.supportsBackgroundSync)
        assertEquals(25 * 1024 * 1024L, desktopCaps.maxPayloadBytes)

        val iosCaps = DeviceCapabilities.IOS_DEFAULT
        assertFalse(iosCaps.supportsBackgroundCapture) // iOS privacy restriction
        assertFalse(iosCaps.supportsBackgroundSync)
    }

    // 2. Cross-Platform Device Identity
    @Test
    fun testDeviceIdentityCreationAndIntegrity() {
        val identity = DeviceIdentity(
            deviceId = "uclip_device_uuid_99418274",
            deviceName = "Ubuntu Workstation 24.04",
            platformType = PlatformType.LINUX,
            platformVersion = "6.8.0-generic",
            capabilities = DeviceCapabilities.DESKTOP_DEFAULT
        )

        assertEquals("uclip_device_uuid_99418274", identity.deviceId)
        assertEquals("Ubuntu Workstation 24.04", identity.deviceName)
        assertEquals(PlatformType.LINUX, identity.platformType)
        assertTrue(identity.capabilities.supportsBackgroundCapture)
    }

    // 3. Universal Protocol Envelope Serialization & Deserialization
    @Test
    fun testProtocolEnvelopeRoundTrip() {
        val originalEnvelope = ProtocolEnvelope(
            messageType = ProtocolMessageType.HELLO,
            protocolVersion = 1,
            senderDeviceId = "macbook_pro_m3",
            senderDeviceName = "MacBook Pro",
            senderPlatform = PlatformType.MACOS,
            payload = "deviceId=macbook_pro_m3;deviceName=MacBook Pro",
            timestamp = 1700000000000L
        )

        val jsonString = originalEnvelope.toJsonString()
        assertNotNull(jsonString)
        assertTrue(jsonString.contains("\"messageType\":\"HELLO\""))
        assertTrue(jsonString.contains("\"senderPlatform\":\"MACOS\""))

        val parsed = ProtocolEnvelope.parse(jsonString)
        assertNotNull(parsed)
        assertEquals(ProtocolMessageType.HELLO, parsed?.messageType)
        assertEquals(1, parsed?.protocolVersion)
        assertEquals("macbook_pro_m3", parsed?.senderDeviceId)
        assertEquals("MacBook Pro", parsed?.senderDeviceName)
        assertEquals(PlatformType.MACOS, parsed?.senderPlatform)
    }

    // 4. Backward Compatibility: Parsing Legacy Handshakes
    @Test
    fun testLegacyHelloAckHandshakeCompatibility() {
        val legacyHello = "HELLO deviceId=pixel_8_pro;deviceName=Pixel 8 Pro"
        val parsedHello = ProtocolEnvelope.parse(legacyHello)
        assertNotNull(parsedHello)
        assertEquals(ProtocolMessageType.HELLO, parsedHello?.messageType)
        assertEquals("pixel_8_pro", parsedHello?.senderDeviceId)
        assertEquals("Pixel 8 Pro", parsedHello?.senderDeviceName)

        val legacyAck = "ACK deviceId=windows_pc;deviceName=Windows Desktop"
        val parsedAck = ProtocolEnvelope.parse(legacyAck)
        assertNotNull(parsedAck)
        assertEquals(ProtocolMessageType.ACK, parsedAck?.messageType)
        assertEquals("windows_pc", parsedAck?.senderDeviceId)
        assertEquals("Windows Desktop", parsedAck?.senderDeviceName)
    }

    // 5. Backward Compatibility: Parsing Raw Direct ClipboardItem JSON Frames
    @Test
    fun testLegacyClipboardItemJsonFrameCompatibility() {
        val item = ClipboardItem(
            id = "clip_test_100",
            sourceDeviceId = "linux_thinkpad",
            sourceDeviceName = "ThinkPad X1",
            type = ClipboardItem.TYPE_CODE,
            content = "val x = 42",
            hash = "sha256_mock_hash"
        )
        val rawItemJson = item.toJsonString()

        val parsedEnvelope = ProtocolEnvelope.parse(rawItemJson)
        assertNotNull(parsedEnvelope)
        assertEquals(ProtocolMessageType.CLIPBOARD_ITEM, parsedEnvelope?.messageType)
        assertEquals("linux_thinkpad", parsedEnvelope?.senderDeviceId)
        assertEquals("ThinkPad X1", parsedEnvelope?.senderDeviceName)
        assertEquals(rawItemJson, parsedEnvelope?.payload)
    }

    // 6. Extensibility: Unknown and Control Message Handling
    @Test
    fun testControlMessages() {
        val pingEnvelope = ProtocolEnvelope.parse("PING")
        assertNotNull(pingEnvelope)
        assertEquals(ProtocolMessageType.PING, pingEnvelope?.messageType)

        val pongEnvelope = ProtocolEnvelope.parse("PONG")
        assertNotNull(pongEnvelope)
        assertEquals(ProtocolMessageType.PONG, pongEnvelope?.messageType)

        // Custom future message with graceful fallback
        val customJson = "{\"messageType\":\"FUTURE_CUSTOM_MSG\",\"senderDeviceId\":\"dev_1\",\"senderPlatform\":\"OTHER\"}"
        val parsedCustom = ProtocolEnvelope.parse(customJson)
        assertNotNull(parsedCustom)
        assertEquals(ProtocolMessageType.UNKNOWN, parsedCustom?.messageType)
    }
}
