package com.example.core.identity

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.clipboard.ClipboardCoreManager
import com.example.data.database.ClipboardDatabase
import com.example.data.repository.ClipboardRepository
import com.example.sync.transport.LocalWifiTransport
import com.example.ui.viewmodel.MainViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Milestone591UnifiedDeviceIdentityTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Clear preferences and cache before each test
        context.getSharedPreferences("uclip_device_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        DeviceIdentityManager.resetCacheForTesting(null)
    }

    @Test
    fun testDeviceReceivesAuthoritativeIdentity() {
        val deviceId = DeviceIdentityManager.getLocalDeviceId(context)
        assertNotNull("Device ID must not be null", deviceId)
        assertTrue("Device ID must not be blank", deviceId.isNotBlank())
        assertTrue("Authoritative device ID must start with uclip_dev_", deviceId.startsWith("uclip_dev_"))

        val identity = DeviceIdentityManager.getLocalIdentity(context)
        assertEquals(deviceId, identity.deviceId)
        assertNotNull(identity.deviceName)
        assertTrue(identity.deviceName.isNotBlank())
    }

    @Test
    fun testRepeatedCallsReturnSameIdentity() {
        val id1 = DeviceIdentityManager.getLocalDeviceId(context)
        val id2 = DeviceIdentityManager.getLocalDeviceId(context)
        val id3 = DeviceIdentityManager.getLocalIdentity(context).deviceId
        val id4 = DeviceIdentityManager.getLocalDevice(context).deviceId

        assertEquals("Repeated calls must return the identical ID", id1, id2)
        assertEquals("Identity call must return the same ID", id1, id3)
        assertEquals("Device helper call must return the same ID", id1, id4)
    }

    @Test
    fun testIdentitySurvivesRecreationAndReinitialization() {
        val originalId = DeviceIdentityManager.getLocalDeviceId(context)

        // Simulate process termination / in-memory cache clear
        DeviceIdentityManager.resetCacheForTesting(null)

        // Read again from persistent storage
        val restoredId = DeviceIdentityManager.getLocalDeviceId(context)
        assertEquals("Identity must survive process reinitialization via SharedPreferences", originalId, restoredId)
    }

    @Test
    fun testPreservesPreExistingLegacyIdentity() {
        val existingLegacyId = "dev_legacy_custom_peer_456"
        context.getSharedPreferences("uclip_device_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("local_device_id", existingLegacyId)
            .commit()
        DeviceIdentityManager.resetCacheForTesting(null)

        val retrievedId = DeviceIdentityManager.getLocalDeviceId(context)
        assertEquals("Pre-existing stored identity must be preserved to avoid breaking peer trust", existingLegacyId, retrievedId)
    }

    @Test
    fun testMainViewModelAndTransportShareAuthoritativeIdentity() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val authoritativeId = DeviceIdentityManager.getLocalDeviceId(app)

        val transport = LocalWifiTransport(context = app, port = 54390)
        assertEquals("Transport must use authoritative device ID", authoritativeId, transport.localDeviceId)

        val db = ClipboardDatabase.getInstance(app)
        val repository = ClipboardRepository(db.clipboardItemDao())
        val clipboardCore = ClipboardCoreManager.getInstance(app, repository)

        val viewModel = MainViewModel(
            application = app,
            repository = repository,
            localWifiTransport = transport
        )

        val localDeviceInVm = viewModel.devices.value.firstOrNull { it.isLocalDevice }
        assertNotNull("Local device must be present in MainViewModel", localDeviceInVm)
        assertEquals("MainViewModel local device must use authoritative device ID", authoritativeId, localDeviceInVm?.deviceId)
        assertEquals("MainViewModel local device must use authoritative device Name", DeviceIdentityManager.getLocalDeviceName(app), localDeviceInVm?.deviceName)
    }

    @Test
    fun testDeviceIdentityNotDerivedFromBuildModel() {
        val deviceId = DeviceIdentityManager.getLocalDeviceId(context)

        // Ensure we are NOT constructing "dev_local_${Build.MODEL}"
        val rawModel = try { android.os.Build.MODEL } catch (e: Throwable) { "" }
        val legacyModelPattern = "dev_local_${rawModel.replace(" ", "_")}"

        assertFalse("Device ID must not be the raw Build.MODEL pattern", deviceId == legacyModelPattern)
        assertTrue("Device ID must be persistent UUID-based", deviceId.startsWith("uclip_dev_"))
    }

    @Test
    fun testCustomIdOverrideHonoredInTests() {
        val customId = "test_custom_override_id_789"
        val resolvedId = DeviceIdentityManager.getLocalDeviceId(context, customId = customId)
        assertEquals(customId, resolvedId)
    }
}
