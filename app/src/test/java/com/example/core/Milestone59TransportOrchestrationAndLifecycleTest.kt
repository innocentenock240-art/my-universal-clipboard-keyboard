package com.example.core

import com.example.core.adapter.TransportAdapter
import com.example.core.transport.NetworkPresenceMonitor
import com.example.core.transport.TransportManager
import com.example.core.transport.TransportType
import com.example.data.model.ClipboardItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class Milestone59TransportOrchestrationAndLifecycleTest {

    // Mock transport adapter for testing orchestration and failover
    private class TestTransportAdapter(
        override val transportName: String,
        override var isAvailable: Boolean = true,
        private val shouldSucceed: Boolean = true
    ) : TransportAdapter {
        val sentItems = mutableListOf<Pair<ClipboardItem, String>>()
        val incomingStream = MutableSharedFlow<ClipboardItem>()

        override suspend fun startTransport() {}
        override suspend fun stopTransport() {}

        override suspend fun sendItem(item: ClipboardItem, targetDeviceId: String): Boolean {
            if (!shouldSucceed) return false
            sentItems.add(item to targetDeviceId)
            return true
        }

        override fun observeIncomingItems(): Flow<ClipboardItem> = incomingStream
    }

    private fun createSampleItem(id: String = "test_item_1", content: String = "Hello World") =
        ClipboardItem(
            id = id,
            content = content,
            type = "TEXT",
            createdAt = System.currentTimeMillis(),
            sourceDeviceId = "dev_local_phone",
            sourceDeviceName = "Local Android Phone",
            sizeBytes = content.toByteArray().size.toLong()
        )

    @Test
    fun testDefaultPriorityOrderClassification() {
        val wifi = TestTransportAdapter("LocalWifiTransport")
        val p2p = TestTransportAdapter("WifiDirectTransportAdapter")
        val bt = TestTransportAdapter("BluetoothTransportAdapter")

        val manager = TransportManager(listOf(bt, wifi, p2p))
        val sorted = manager.getSortedTransports()

        // Verify order is Wi-Fi LAN, Wi-Fi Direct, Bluetooth Classic
        assertEquals("LocalWifiTransport", sorted[0].transportName)
        assertEquals("WifiDirectTransportAdapter", sorted[1].transportName)
        assertEquals("BluetoothTransportAdapter", sorted[2].transportName)
    }

    @Test
    fun testAutomaticFailoverToSecondaryTransport() = runBlocking {
        // Wi-Fi LAN fails, so failover should route to Wi-Fi Direct
        val failingWifi = TestTransportAdapter("LocalWifiTransport", isAvailable = true, shouldSucceed = false)
        val workingP2p = TestTransportAdapter("WifiDirectTransportAdapter", isAvailable = true, shouldSucceed = true)
        val workingBt = TestTransportAdapter("BluetoothTransportAdapter", isAvailable = true, shouldSucceed = true)

        val manager = TransportManager(listOf(failingWifi, workingP2p, workingBt))
        val item = createSampleItem()

        val success = manager.sendItem(item, "target_device_1")

        assertTrue("Sending should succeed via failover", success)
        assertEquals(0, failingWifi.sentItems.size)
        assertEquals(1, workingP2p.sentItems.size)
        assertEquals(item.id, workingP2p.sentItems[0].first.id)
        assertEquals(0, workingBt.sentItems.size)
    }

    @Test
    fun testCustomPriorityReordering() {
        val wifi = TestTransportAdapter("LocalWifiTransport")
        val bt = TestTransportAdapter("BluetoothTransportAdapter")

        val manager = TransportManager(listOf(wifi, bt))

        // Set Bluetooth to higher priority than Wi-Fi
        manager.setPriorityOrder(listOf(TransportType.BLUETOOTH_CLASSIC, TransportType.WIFI_LAN))

        val sorted = manager.getSortedTransports()
        assertEquals("BluetoothTransportAdapter", sorted[0].transportName)
        assertEquals("LocalWifiTransport", sorted[1].transportName)
    }

    @Test
    fun testAllTransportsUnavailableReturnsFalse() = runBlocking {
        val unavailableWifi = TestTransportAdapter("LocalWifiTransport", isAvailable = false, shouldSucceed = true)
        val unavailableBt = TestTransportAdapter("BluetoothTransportAdapter", isAvailable = false, shouldSucceed = true)

        val manager = TransportManager(listOf(unavailableWifi, unavailableBt))
        val item = createSampleItem()

        val success = manager.sendItem(item)
        assertFalse("Should fail when all transports are unavailable", success)
    }

    @Test
    fun testIncomingStreamMerging() = runBlocking {
        val wifi = TestTransportAdapter("LocalWifiTransport")
        val bt = TestTransportAdapter("BluetoothTransportAdapter")
        val manager = TransportManager(listOf(wifi, bt))

        val item1 = createSampleItem("item_wifi", "From Wi-Fi")
        val item2 = createSampleItem("item_bt", "From Bluetooth")

        val stream = manager.observeAllIncomingItems()
        val receivedItems = mutableListOf<ClipboardItem>()
        val collectJob = launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            stream.collect { item -> receivedItems.add(item) }
        }

        wifi.incomingStream.emit(item1)
        bt.incomingStream.emit(item2)

        assertEquals(2, receivedItems.size)
        assertEquals("item_wifi", receivedItems[0].id)
        assertEquals("item_bt", receivedItems[1].id)

        collectJob.cancel()
    }

    @Test
    fun testNetworkPresenceMonitorState() {
        val context = RuntimeEnvironment.getApplication()
        val monitor = NetworkPresenceMonitor(context)

        assertNotNull(monitor.isNetworkConnected)
        assertNotNull(monitor.isWifiAvailable)

        var restoredFired = false
        monitor.onNetworkRestored = { restoredFired = true }
        monitor.startMonitoring()
        monitor.stopMonitoring()
    }
}
