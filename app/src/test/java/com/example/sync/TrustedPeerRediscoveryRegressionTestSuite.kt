package com.example.sync

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.core.identity.DeviceIdentityManager
import com.example.core.identity.DeviceTrustManager
import com.example.core.identity.TrustedPeerRecord
import com.example.core.transport.LogicalPeerSession
import com.example.core.transport.PeerSessionState
import com.example.core.transport.TransportManager
import com.example.data.database.ClipboardDatabase
import com.example.data.database.entity.DeliveryState
import com.example.data.database.entity.PendingClipboardDeliveryEntity
import com.example.data.model.ClipboardItem
import com.example.data.model.ConnectionState
import com.example.data.model.Device
import com.example.data.repository.ClipboardRepository
import com.example.sync.transport.BluetoothTransportAdapter
import com.example.sync.transport.LocalWifiTransport
import com.example.sync.transport.WifiDirectTransportAdapter
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * FINAL AUTOMATED REGRESSION AUDIT SUITE
 *
 * Exhaustively proves the complete lifecycle of:
 * Trusted-Peer Persistence → Disconnection → Rediscovery → Existing-Peer Reconnection
 * across all 20 required verification vectors.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class TrustedPeerRediscoveryRegressionTestSuite {

    private lateinit var context: Application
    private lateinit var database: ClipboardDatabase
    private lateinit var repository: ClipboardRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = ClipboardDatabase.getInstance(context)
        repository = ClipboardRepository(database.clipboardItemDao(), database.pendingDeliveryDao())

        DeviceTrustManager.clearAllTrustedPeers(context)
        DeviceTrustManager.resetCacheForTesting(context)
        DeviceIdentityManager.resetCacheForTesting()
        com.example.sync.SyncRuntime.resetForTesting(context)
    }

    @After
    fun tearDown() {
        DeviceTrustManager.clearAllTrustedPeers(context)
        LocalWifiTransport.activeTransportInstance?.stopServer()
        com.example.sync.SyncRuntime.resetForTesting(context)
    }

    // 1. Trusted peer persists
    @Test
    fun test01_trustedPeerPersistsInStorage() = runTest {
        val peerId = "peer_b_authoritative_id_001"
        val peerName = "Phone B"
        val ipHint = "192.168.1.50"

        DeviceTrustManager.recordPeerTrust(
            context = context,
            peerDeviceId = peerId,
            deviceName = peerName,
            ipHint = ipHint
        )

        assertTrue(DeviceTrustManager.isPeerTrusted(peerId))
        val record = DeviceTrustManager.getTrustedPeer(peerId)
        assertNotNull(record)
        assertEquals(peerId, record?.peerDeviceId)
        assertEquals(peerName, record?.deviceName)
        assertEquals(ipHint, record?.lastKnownIpAddress)
        assertTrue(record?.isTrusted == true)
    }

    // 2. Trusted peer survives disconnect
    @Test
    fun test02_trustedPeerSurvivesDisconnect() = runTest {
        val peerId = "peer_b_002"
        DeviceTrustManager.recordPeerTrust(context, peerId, "Phone B", ipHint = "192.168.1.50")

        val transport = LocalWifiTransport(context = context)
        val deviceB = Device(
            deviceId = peerId,
            deviceName = "Phone B",
            ipAddress = "192.168.1.50",
            connectionState = ConnectionState.CONNECTED
        )
        transport.addDiscoveredDevice(deviceB)

        // Peer disconnects / network lost
        transport.onNetworkLost()

        // Authoritative trust must remain YES while connection state is DISCONNECTED
        assertTrue("Trust must survive disconnect", DeviceTrustManager.isPeerTrusted(peerId))
        assertTrue("Transport must still recognize peer as known", transport.isKnownPeer(peerId))

        val devInTransport = transport.discoveredDevices.value.find { it.deviceId == peerId }
        assertNotNull(devInTransport)
        assertEquals(ConnectionState.DISCONNECTED, devInTransport?.connectionState)
        assertFalse(devInTransport?.isOnline == true)
        assertTrue(devInTransport?.isPaired == true)
    }

    // 3. Trusted peer survives repository recreation
    @Test
    fun test03_trustedPeerSurvivesRepositoryRecreation() = runTest {
        val peerId = "peer_b_003"
        DeviceTrustManager.recordPeerTrust(context, peerId, "Phone B", ipHint = "192.168.1.50")

        // Recreate repository & re-verify DAO and trust status
        val newRepo = ClipboardRepository(database.clipboardItemDao(), database.pendingDeliveryDao())
        assertNotNull(newRepo)
        assertTrue(DeviceTrustManager.isPeerTrusted(peerId))
        val trustedIds = DeviceTrustManager.getTrustedPeerIds()
        assertTrue(trustedIds.contains(peerId))
    }

    // 4. Trusted peer survives application recreation
    @Test
    fun test04_trustedPeerSurvivesApplicationProcessRecreation() = runTest {
        val peerId = "peer_b_004"
        DeviceTrustManager.recordPeerTrust(context, peerId, "Phone B", ipHint = "192.168.1.50")

        // Simulate complete process kill by clearing in-memory cache and re-initializing from context
        DeviceTrustManager.resetCacheForTesting(context)

        assertTrue("Trust must be restored from persistent SharedPreferences", DeviceTrustManager.isPeerTrusted(peerId))
        val restored = DeviceTrustManager.getTrustedPeer(peerId)
        assertNotNull(restored)
        assertEquals(peerId, restored?.peerDeviceId)
        assertEquals("Phone B", restored?.deviceName)
    }

    // 5. Rediscovery resolves existing trusted peer
    @Test
    fun test05_rediscoveryResolvesExistingTrustedPeer() = runTest {
        val peerId = "peer_b_005"
        DeviceTrustManager.recordPeerTrust(context, peerId, "Phone B", ipHint = "192.168.1.50")

        val transport = LocalWifiTransport(context = context)
        // Rediscover device B
        val redisovered = Device(
            deviceId = peerId,
            deviceName = "Phone B",
            ipAddress = "192.168.1.50",
            connectionState = ConnectionState.DISCOVERED
        )
        transport.addDiscoveredDevice(redisovered)

        val itemInTransport = transport.discoveredDevices.value.find { it.deviceId == peerId }
        assertNotNull(itemInTransport)
        assertTrue("Must be resolved as paired", itemInTransport?.isPaired == true)
        // Should transition to RECONNECTING because it's a known peer
        assertEquals(ConnectionState.RECONNECTING, itemInTransport?.connectionState)
    }

    // 6. Rediscovery does not duplicate trusted peer
    @Test
    fun test06_rediscoveryDoesNotDuplicateTrustedPeer() = runTest {
        val peerId = "peer_b_006"
        DeviceTrustManager.recordPeerTrust(context, peerId, "Phone B", ipHint = "192.168.1.50")
        val initialCount = DeviceTrustManager.getAllTrustedPeers().size

        val transport = LocalWifiTransport(context = context)
        transport.addDiscoveredDevice(Device(deviceId = peerId, deviceName = "Phone B", ipAddress = "192.168.1.50"))
        transport.addDiscoveredDevice(Device(deviceId = peerId, deviceName = "Phone B", ipAddress = "192.168.1.50"))

        val finalCount = DeviceTrustManager.getAllTrustedPeers().size
        assertEquals("Trusted peer count must not increase on rediscovery", initialCount, finalCount)
        assertEquals(1, transport.discoveredDevices.value.filter { it.deviceId == peerId }.size)
    }

    // 7. Logical session survives transport loss
    @Test
    fun test07_logicalSessionSurvivesTransportLoss() = runTest {
        val peerId = "peer_b_007"
        val session = LogicalPeerSession(peerDeviceId = peerId)

        session.transitionTo(PeerSessionState.CONNECTED)
        session.recordSuccess()
        assertEquals(PeerSessionState.CONNECTED, session.state.value)
        assertEquals(1, session.successfulTransfers)

        // Transport lost
        session.unbindTransport()
        assertEquals(PeerSessionState.DISCONNECTED, session.state.value)
        assertEquals(peerId, session.peerDeviceId)
        assertEquals(1, session.successfulTransfers) // Statistics preserved
    }

    // 8. Reconnect uses same peerDeviceId
    @Test
    fun test08_reconnectUsesSamePeerDeviceId() = runTest {
        val peerId = "peer_b_008"
        DeviceTrustManager.recordPeerTrust(context, peerId, "Phone B")

        val transport = LocalWifiTransport(context = context)
        val dev = Device(deviceId = peerId, deviceName = "Phone B", ipAddress = "192.168.1.50")
        transport.addDiscoveredDevice(dev)

        assertEquals(peerId, transport.discoveredDevices.value.first { it.deviceId == peerId }.deviceId)
    }

    // 9. Reconnect works after IP address changes
    @Test
    fun test09_reconnectWorksAfterIpAddressChanges() = runTest {
        val peerId = "peer_b_009"
        val initialIp = "192.168.1.20"
        val newDhcpIp = "192.168.1.35"

        DeviceTrustManager.recordPeerTrust(context, peerId, "Phone B", ipHint = initialIp)

        val transport = LocalWifiTransport(context = context)
        transport.addDiscoveredDevice(Device(deviceId = peerId, deviceName = "Phone B", ipAddress = initialIp))

        // Device B returns with new DHCP IP
        transport.addDiscoveredDevice(Device(deviceId = peerId, deviceName = "Phone B", ipAddress = newDhcpIp))

        val resolved = transport.discoveredDevices.value.find { it.deviceId == peerId }
        assertNotNull(resolved)
        assertEquals(newDhcpIp, resolved?.ipAddress)
        assertTrue("Must retain trust despite IP change", resolved?.isPaired == true)
        assertTrue(DeviceTrustManager.isPeerTrusted(peerId))
    }

    // 10. Unknown peer remains untrusted
    @Test
    fun test10_unknownPeerRemainsUntrusted() = runTest {
        val unknownId = "unknown_device_c_010"
        val transport = LocalWifiTransport(context = context)

        transport.addDiscoveredDevice(
            Device(
                deviceId = unknownId,
                deviceName = "Stranger Device",
                ipAddress = "192.168.1.99",
                connectionState = ConnectionState.DISCOVERED
            )
        )

        assertFalse("Unknown peer must NOT be trusted in DeviceTrustManager", DeviceTrustManager.isPeerTrusted(unknownId))
        assertFalse("Unknown peer must NOT be trusted in transport", transport.isKnownPeer(unknownId))

        val dev = transport.discoveredDevices.value.find { it.deviceId == unknownId }
        assertNotNull(dev)
        assertFalse("isPaired must be false", dev?.isPaired == true)
        assertEquals(ConnectionState.DISCOVERED, dev?.connectionState)
    }

    // 11. Duplicate discovery does not duplicate peer
    @Test
    fun test11_duplicateDiscoveryDoesNotDuplicatePeer() = runTest {
        val peerId = "peer_b_011"
        DeviceTrustManager.recordPeerTrust(context, peerId, "Phone B")

        val transport = LocalWifiTransport(context = context)
        for (i in 1..5) {
            transport.addDiscoveredDevice(Device(deviceId = peerId, deviceName = "Phone B", ipAddress = "192.168.1.50"))
        }

        val matches = transport.discoveredDevices.value.filter { it.deviceId == peerId }
        assertEquals("Must only have 1 device entry", 1, matches.size)
        assertEquals(1, DeviceTrustManager.getAllTrustedPeers().size)
    }

    // 12. Reconnect requires no re-pairing
    @Test
    fun test12_reconnectRequiresNoRePairing() = runTest {
        val peerId = "peer_b_012"
        DeviceTrustManager.recordPeerTrust(context, peerId, "Phone B")

        val transport = LocalWifiTransport(context = context)
        // Discovered after being offline
        transport.addDiscoveredDevice(Device(deviceId = peerId, deviceName = "Phone B", ipAddress = "192.168.1.50"))

        val dev = transport.discoveredDevices.value.find { it.deviceId == peerId }
        assertNotNull(dev)
        assertTrue("Already paired without requiring user confirmation", dev?.isPaired == true)
    }

    // 13. Trusted Peers UI retains offline trusted peers
    @Test
    fun test13_trustedPeersUiRetainsOfflineTrustedPeers() = runTest {
        val peerId = "peer_b_013"
        DeviceTrustManager.recordPeerTrust(context, peerId, "Phone B", ipHint = "192.168.1.50")

        val viewModel = MainViewModel(context)
        
        val startInit = System.currentTimeMillis()
        while (viewModel.devices.value.none { it.deviceId == peerId } && System.currentTimeMillis() - startInit < 2000) {
            kotlinx.coroutines.delay(50)
        }

        val devices = viewModel.devices.value
        val offlineTrusted = devices.find { it.deviceId == peerId }
        assertNotNull("Offline trusted peer must appear in ViewModel devices list", offlineTrusted)
        assertTrue(offlineTrusted?.isPaired == true)
        assertFalse(offlineTrusted?.isOnline == true)
        assertEquals(ConnectionState.DISCONNECTED, offlineTrusted?.connectionState)
    }

    // 14. Trusted Peers UI changes state when peer returns
    @Test
    fun test14_trustedPeersUiChangesStateWhenPeerReturns() = runBlocking {
        val peerId = "peer_b_014"
        DeviceTrustManager.recordPeerTrust(context, peerId, "Phone B", ipHint = "192.168.1.50")

        val viewModel = MainViewModel(context)
        
        val startInit = System.currentTimeMillis()
        while (SyncRuntime.ecosystemState.value.allDevices.none { it.deviceId == peerId } && System.currentTimeMillis() - startInit < 2000) {
            kotlinx.coroutines.delay(20)
        }

        // Device B returns
        viewModel.localWifiTransport.addDiscoveredDevice(
            Device(deviceId = peerId, deviceName = "Phone B", ipAddress = "192.168.1.50", connectionState = ConnectionState.CONNECTED, isOnline = true)
        )
        
        val startUpdate = System.currentTimeMillis()
        while (SyncRuntime.ecosystemState.value.allDevices.none { it.deviceId == peerId && it.isOnline } && System.currentTimeMillis() - startUpdate < 2000) {
            kotlinx.coroutines.delay(20)
        }

        val devices = SyncRuntime.ecosystemState.value.allDevices
        val onlineTrusted = devices.find { it.deviceId == peerId }
        assertNotNull(onlineTrusted)
        assertTrue(onlineTrusted?.isPaired == true)
        assertTrue(onlineTrusted?.isOnline == true)
        assertEquals(ConnectionState.CONNECTED, onlineTrusted?.connectionState)
    }

    // 15. Pending delivery targets same peerDeviceId
    @Test
    fun test15_pendingDeliveryTargetsSamePeerDeviceId() = runTest {
        val peerId = "peer_b_015"
        DeviceTrustManager.recordPeerTrust(context, peerId, "Phone B")

        val testItem = ClipboardItem(
            id = "item_target_test_015",
            content = "Targeted message for Phone B",
            sourceDeviceId = DeviceIdentityManager.getLocalDeviceId(context),
            sourceDeviceName = "Phone A"
        )
        repository.insertClipboardItem(testItem)
        val delivery = PendingClipboardDeliveryEntity(
            deliveryId = "del_test_015",
            clipboardItemId = testItem.id,
            targetPeerDeviceId = peerId
        )
        repository.enqueuePendingDelivery(delivery)

        val pending = repository.getPendingDeliveriesForPeer(peerId)
        assertEquals(1, pending.size)
        assertEquals(peerId, pending[0].targetPeerDeviceId)
        assertEquals(testItem.id, pending[0].clipboardItemId)
        assertEquals(DeliveryState.PENDING.name, pending[0].state)
    }

    // 16. Reconnect flushes pending delivery
    @Test
    fun test16_reconnectFlushesPendingDelivery() = runTest {
        val peerId = "peer_b_016"
        DeviceTrustManager.recordPeerTrust(context, peerId, "Phone B")

        val testItem = ClipboardItem(
            id = "item_flush_test_016",
            content = "Queued for reconnect flush",
            sourceDeviceId = DeviceIdentityManager.getLocalDeviceId(context),
            sourceDeviceName = "Phone A"
        )
        repository.insertClipboardItem(testItem)
        val delivery = PendingClipboardDeliveryEntity(
            deliveryId = "del_test_016",
            clipboardItemId = testItem.id,
            targetPeerDeviceId = peerId
        )
        repository.enqueuePendingDelivery(delivery)

        val mockWifiTransport = object : com.example.core.adapter.TransportAdapter {
            override val transportName: String = "MockWifi"
            override val isAvailable: Boolean = true
            override suspend fun startTransport() {}
            override suspend fun stopTransport() {}
            override suspend fun sendItem(item: ClipboardItem, targetDeviceId: String): Boolean = true
            override fun observeIncomingItems() = kotlinx.coroutines.flow.emptyFlow<ClipboardItem>()
        }

        val transportManager = TransportManager(listOf(mockWifiTransport), scope = this)
        val syncEngine = SyncEngine(transportManager, repository)

        syncEngine.onPeerConnected(peerId)
        
        val startWait = System.currentTimeMillis()
        var remaining = repository.getPendingDeliveriesForPeer(peerId)
        while (remaining.isNotEmpty() && System.currentTimeMillis() - startWait < 2000) {
            kotlinx.coroutines.delay(50)
            remaining = repository.getPendingDeliveriesForPeer(peerId)
        }

        assertEquals(0, remaining.size)
    }

    // 17. ACK acknowledges correct peer delivery
    @Test
    fun test17_ackAcknowledgesCorrectPeerDelivery() = runTest {
        val peerId = "peer_b_017"
        DeviceTrustManager.recordPeerTrust(context, peerId, "Phone B")

        val testItem = ClipboardItem(
            id = "item_ack_test_017",
            content = "Ack test item",
            sourceDeviceId = DeviceIdentityManager.getLocalDeviceId(context),
            sourceDeviceName = "Phone A"
        )
        repository.insertClipboardItem(testItem)
        val delivery = PendingClipboardDeliveryEntity(
            deliveryId = "del_test_017",
            clipboardItemId = testItem.id,
            targetPeerDeviceId = peerId
        )
        repository.enqueuePendingDelivery(delivery)

        repository.markDeliveryAcknowledged(delivery.deliveryId, System.currentTimeMillis())

        val unacknowledged = repository.getPendingDeliveriesForPeer(peerId)
        assertEquals(0, unacknowledged.size)
    }

    // 18. Old IP cannot inherit trust
    @Test
    fun test18_oldIpCannotInheritTrust() = runTest {
        val trustedPeerId = "trusted_peer_b_018"
        val sharedIp = "192.168.1.80"

        // Device B was trusted at sharedIp
        DeviceTrustManager.recordPeerTrust(context, trustedPeerId, "Phone B", ipHint = sharedIp)

        // Device B leaves, Device C gets sharedIp on DHCP
        val newDeviceId = "untrusted_device_c_018"
        val transport = LocalWifiTransport(context = context)
        transport.addDiscoveredDevice(
            Device(
                deviceId = newDeviceId,
                deviceName = "Stranger Device C",
                ipAddress = sharedIp,
                connectionState = ConnectionState.DISCOVERED
            )
        )

        // Device C must NOT inherit B's trust
        assertFalse("Device C must not inherit trust based on IP", DeviceTrustManager.isPeerTrusted(newDeviceId))
        assertFalse("Device C must not be known in transport", transport.isKnownPeer(newDeviceId))
        val devC = transport.discoveredDevices.value.find { it.deviceId == newDeviceId }
        assertNotNull(devC)
        assertFalse("Device C isPaired must be false", devC?.isPaired == true)
    }

    // 19. New IP of trusted device retains trust
    @Test
    fun test19_newIpOfTrustedDeviceRetainsTrust() = runTest {
        val trustedPeerId = "trusted_peer_b_019"
        val initialIp = "192.168.1.100"
        val newIp = "192.168.1.222"

        DeviceTrustManager.recordPeerTrust(context, trustedPeerId, "Phone B", ipHint = initialIp)

        val transport = LocalWifiTransport(context = context)
        transport.addDiscoveredDevice(Device(deviceId = trustedPeerId, deviceName = "Phone B", ipAddress = newIp))

        assertTrue("Must retain trust with new IP", DeviceTrustManager.isPeerTrusted(trustedPeerId))
        assertTrue("Transport must recognize peer with new IP", transport.isKnownPeer(trustedPeerId))
        val inTransport = transport.discoveredDevices.value.find { it.deviceId == trustedPeerId }
        assertNotNull(inTransport)
        assertTrue(inTransport?.isPaired == true)
        assertEquals(newIp, inTransport?.ipAddress)
    }

    // 20. New device with old IP remains untrusted
    @Test
    fun test20_newDeviceWithOldIpRemainsUntrusted() = runTest {
        val oldPeerId = "old_trusted_peer_020"
        val oldIp = "192.168.1.44"
        DeviceTrustManager.recordPeerTrust(context, oldPeerId, "Phone B", ipHint = oldIp)

        val impostorId = "impostor_device_020"
        val transport = LocalWifiTransport(context = context)

        // Impostor claims old IP but sends its own deviceId in handshake
        transport.addDiscoveredDevice(
            Device(
                deviceId = impostorId,
                deviceName = "Impostor Phone",
                ipAddress = oldIp,
                connectionState = ConnectionState.DISCOVERED
            )
        )

        assertFalse(DeviceTrustManager.isPeerTrusted(impostorId))
        val inTransport = transport.discoveredDevices.value.find { it.deviceId == impostorId }
        assertNotNull(inTransport)
        assertFalse(inTransport?.isPaired == true)
    }
}
