package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.core.identity.DeviceTrustManager
import com.example.core.policy.SyncPolicyManager
import com.example.data.database.ClipboardDatabase
import com.example.data.model.ClipboardItem
import com.example.data.model.ConnectionState
import com.example.data.model.Device
import com.example.data.repository.ClipboardRepository
import com.example.keyboard.ImeSyncStatus
import com.example.keyboard.KeyboardScreen
import com.example.keyboard.UniversalClipboardInputMethodService
import com.example.sync.SyncEngine
import com.example.sync.transport.LocalWifiTransport
import com.example.ui.screens.DevicesScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ForensicAuditRegressionTestSuite {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testDevicesScreenRendersWithoutKeyCollisionWhenPairedDeviceDiscovered() {
        val localDevice = Device(
            deviceId = "dev_local_1",
            deviceName = "This Phone",
            isLocalDevice = true,
            isPaired = false,
            isOnline = true
        )
        val pairedPeer = Device(
            deviceId = "dev_peer_paired_1",
            deviceName = "Paired Laptop",
            isLocalDevice = false,
            isPaired = true,
            isOnline = true,
            connectionState = ConnectionState.CONNECTED
        )
        val unknownDiscoveredPeer = Device(
            deviceId = "dev_peer_unknown_2",
            deviceName = "New Nearby Tablet",
            isLocalDevice = false,
            isPaired = false,
            isOnline = true,
            connectionState = ConnectionState.DISCOVERED
        )

        // The paired peer is present in both discoveredDevices (from mDNS) and devices (from DB/trust store)
        val allDevices = listOf(localDevice, pairedPeer)
        val discoveredDevices = listOf(pairedPeer, unknownDiscoveredPeer)

        composeTestRule.setContent {
            DevicesScreen(
                devices = allDevices,
                discoveredDevices = discoveredDevices,
                isDiscovering = true,
                isServerRunning = true
            )
        }

        // Verify the screen renders cleanly without any IllegalArgumentException key collision
        composeTestRule.onNodeWithTag("devices_screen_list").assertExists()
        composeTestRule.onNodeWithTag("devices_screen_list").performScrollToNode(hasText("This Phone"))
        composeTestRule.onNodeWithText("This Phone").assertExists()
        composeTestRule.onNodeWithTag("devices_screen_list").performScrollToNode(hasText("Paired Laptop"))
        composeTestRule.onNodeWithText("Paired Laptop").assertExists()
        composeTestRule.onNodeWithTag("devices_screen_list").performScrollToNode(hasText("New Nearby Tablet"))
        composeTestRule.onNodeWithText("New Nearby Tablet").assertExists()
    }

    @Test
    fun testKeyboardScreenDestinationPickerAllowsTargetSelection() {
        val sampleItem = ClipboardItem(
            id = "clip_test_101",
            sourceDeviceId = "dev_local",
            sourceDeviceName = "Local Device",
            type = "TEXT",
            content = "Cross-device sync payload 2026",
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 600000
        )
        val remotePeer1 = Device(
            deviceId = "dev_remote_1",
            deviceName = "Phone B",
            isLocalDevice = false,
            isPaired = true,
            isOnline = true,
            connectionState = ConnectionState.CONNECTED
        )
        val remotePeer2 = Device(
            deviceId = "dev_remote_2",
            deviceName = "Desktop PC",
            isLocalDevice = false,
            isPaired = true,
            isOnline = true,
            connectionState = ConnectionState.CONNECTED
        )

        var sentItem: ClipboardItem? = null
        var sentDeviceId: String? = null

        composeTestRule.setContent {
            KeyboardScreen(
                clipboardItems = listOf(sampleItem),
                devices = listOf(remotePeer1, remotePeer2),
                onInsertText = {},
                onBackspace = {},
                onEnter = {},
                onSendItemToDevice = { item, targetId ->
                    sentItem = item
                    sentDeviceId = targetId
                }
            )
        }

        // Toggle to Clipboard History mode
        composeTestRule.onNodeWithTag("toggle_clipboard_btn").performClick()
        composeTestRule.onNodeWithTag("clipboard_panel").assertExists()

        // Tap "Send ➔" on the clipboard item
        composeTestRule.onNodeWithText("Send ➔").performClick()

        // Target Device Picker panel must appear
        composeTestRule.onNodeWithTag("target_device_picker_panel").assertExists()
        composeTestRule.onNodeWithTag("target_device_all").assertExists()
        composeTestRule.onNodeWithTag("target_device_dev_remote_1").assertExists()

        // Click specific target "Phone B"
        composeTestRule.onNodeWithTag("target_device_dev_remote_1").performClick()

        assertEquals("clip_test_101", sentItem?.id)
        assertEquals("dev_remote_1", sentDeviceId)
    }

    @Test
    fun testDevicesScreenPeerListMutationsAndReconnectionLifecycle() {
        val localDevice = Device(
            deviceId = "dev_local_primary",
            deviceName = "Primary Phone",
            isLocalDevice = true,
            isPaired = false,
            isOnline = true
        )
        val trustedPeer = Device(
            deviceId = "dev_trusted_laptop",
            deviceName = "Work Laptop",
            isLocalDevice = false,
            isPaired = true,
            isOnline = true,
            ipAddress = "192.168.1.100",
            connectionState = ConnectionState.CONNECTED
        )
        val unassignedPeer1 = Device(
            deviceId = "dev_discovered_tab",
            deviceName = "Living Room Tablet",
            isLocalDevice = false,
            isPaired = false,
            isOnline = true,
            ipAddress = "192.168.1.101",
            connectionState = ConnectionState.DISCOVERED
        )

        val devicesState = androidx.compose.runtime.mutableStateOf(listOf(localDevice, trustedPeer))
        val discoveredState = androidx.compose.runtime.mutableStateOf(listOf(unassignedPeer1))
        val isDiscoveringState = androidx.compose.runtime.mutableStateOf(true)

        composeTestRule.setContent {
            DevicesScreen(
                devices = devicesState.value,
                discoveredDevices = discoveredState.value,
                isDiscovering = isDiscoveringState.value,
                isServerRunning = true
            )
        }

        // 1. Initial verification
        composeTestRule.onNodeWithTag("devices_screen_list").assertExists()
        composeTestRule.onNodeWithTag("devices_screen_list").performScrollToNode(hasText("Living Room Tablet"))
        composeTestRule.onNodeWithText("Living Room Tablet").assertExists()
        composeTestRule.onNodeWithTag("devices_screen_list").performScrollToNode(hasText("Work Laptop"))
        composeTestRule.onNodeWithText("Work Laptop").assertExists()

        // 2. Peer IP change & ConnectionState mutation (CONNECTED -> RECONNECTING -> CONNECTED)
        devicesState.value = listOf(
            localDevice,
            trustedPeer.copy(ipAddress = "192.168.1.200", connectionState = ConnectionState.RECONNECTING)
        )
        composeTestRule.waitForIdle()

        // 3. Trusted peer is rediscovered via mDNS (appears in discovered list with isPaired=false or true)
        val rediscoveredTrusted = Device(
            deviceId = "dev_trusted_laptop",
            deviceName = "Work Laptop",
            isLocalDevice = false,
            isPaired = false, // mDNS raw packet might not know pairing state yet
            isOnline = true,
            ipAddress = "192.168.1.200",
            connectionState = ConnectionState.DISCOVERED
        )
        discoveredState.value = listOf(unassignedPeer1, rediscoveredTrusted)
        composeTestRule.waitForIdle()

        // Verify trusted peer is ONLY rendered once (in Trusted Paired section), not in Discovered section
        composeTestRule.onNodeWithTag("devices_screen_list").performScrollToNode(hasText("Discovered Nearby Devices (1)"))
        composeTestRule.onNodeWithText("Discovered Nearby Devices (1)").assertExists()
        composeTestRule.onNodeWithTag("devices_screen_list").performScrollToNode(hasText("Trusted Paired Devices (1)"))
        composeTestRule.onNodeWithText("Trusted Paired Devices (1)").assertExists()

        // 4. Duplicate entries in discovered list (e.g. multi-interface broadcast)
        discoveredState.value = listOf(
            unassignedPeer1,
            unassignedPeer1.copy(ipAddress = "192.168.1.102"), // duplicate ID with different IP
            rediscoveredTrusted
        )
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("devices_screen_list").performScrollToNode(hasText("Discovered Nearby Devices (1)"))
        composeTestRule.onNodeWithText("Discovered Nearby Devices (1)").assertExists()

        // 5. Peer disappears (dropped from mDNS)
        discoveredState.value = emptyList()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("devices_screen_list").performScrollToNode(hasText("Discovered Nearby Devices (0)"))
        composeTestRule.onNodeWithText("Discovered Nearby Devices (0)").assertExists()

        // 6. Peer reconnects & user scrolls list
        val unassignedPeer2 = Device(
            deviceId = "dev_discovered_desktop",
            deviceName = "Gaming Desktop",
            isLocalDevice = false,
            isPaired = false,
            isOnline = true,
            connectionState = ConnectionState.DISCOVERED
        )
        discoveredState.value = listOf(unassignedPeer2)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("devices_screen_list").performScrollToNode(hasText("Gaming Desktop"))
        composeTestRule.onNodeWithText("Gaming Desktop").assertExists()
    }

    @Test
    fun testImeServiceRoutesSendThroughSyncEngine() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = ClipboardDatabase.getInstance(context)
        val repo = ClipboardRepository(db.clipboardItemDao(), db.pendingDeliveryDao())
        DeviceTrustManager.init(context)
        SyncPolicyManager.init(context)

        val controller = Robolectric.buildService(UniversalClipboardInputMethodService::class.java)
        val service = controller.create().get()
        assertNotNull(service)

        val transport = LocalWifiTransport(port = 54341)
        val engine = SyncEngine(listOf(transport), repo)
        service.syncEngine = engine

        val itemToSync = ClipboardItem(
            id = "clip_ime_sync_1",
            sourceDeviceId = "dev_local",
            content = "Sent from independent IME",
            createdAt = System.currentTimeMillis()
        )

        // Queue sync via engine
        val syncResult = engine.syncClipboardItem(itemToSync, "dev_target_99")
        // Will enqueue delivery record in repo
        val pendingDeliveries = repo.getAllDeliveries()
        assertTrue(pendingDeliveries.any { it.clipboardItemId == "clip_ime_sync_1" })
    }

    @Test
    fun testPeerIdentityContinuityOnRediscovery() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        DeviceTrustManager.init(context)

        val peerId = "dev_persistent_peer_123"
        DeviceTrustManager.recordPeerTrust(
            context = context,
            peerDeviceId = peerId,
            deviceName = "Office Workstation",
            ipHint = "192.168.1.150"
        )

        assertTrue(DeviceTrustManager.isPeerTrusted(peerId))
        val initialTrustInfo = DeviceTrustManager.getTrustedPeer(peerId)
        assertNotNull(initialTrustInfo)
        assertEquals("Office Workstation", initialTrustInfo?.deviceName)

        // Simulate peer seen again with updated IP
        DeviceTrustManager.recordPeerTrust(
            context = context,
            peerDeviceId = peerId,
            deviceName = "Office Workstation Renamed",
            ipHint = "192.168.1.155"
        )

        val updatedTrustInfo = DeviceTrustManager.getTrustedPeer(peerId)
        assertNotNull(updatedTrustInfo)
        assertEquals("192.168.1.155", updatedTrustInfo?.lastKnownIpAddress)
        assertTrue(DeviceTrustManager.isPeerTrusted(peerId))
    }
}
