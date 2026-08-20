package com.example.sync

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.core.identity.DeviceTrustManager
import com.example.core.identity.TrustedPeerRecord
import com.example.core.transport.LogicalPeerSession
import com.example.core.transport.PeerSessionState
import com.example.core.transport.TransportManager
import com.example.data.model.ConnectionState
import com.example.data.model.Device
import com.example.sync.transport.LocalWifiTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class TrustedPeerPersistenceAndReconnectionTest {

    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DeviceTrustManager.clearAllTrustedPeers(context)
        DeviceTrustManager.resetCacheForTesting(context)
    }

    @After
    fun tearDown() {
        DeviceTrustManager.clearAllTrustedPeers(context)
    }

    @Test
    fun testTrustedPeerPersistenceSurvivesAppRestart() = runTest {
        val testPeerId = "peer_test_alpha_123"
        val testPeerName = "Pixel 8 Pro"
        val testIpHint = "192.168.1.55"

        // 1. Establish trust
        DeviceTrustManager.recordPeerTrust(
            context = context,
            peerDeviceId = testPeerId,
            deviceName = testPeerName,
            ipHint = testIpHint
        )

        assertTrue(DeviceTrustManager.isPeerTrusted(testPeerId))
        val recordBefore = DeviceTrustManager.getTrustedPeer(testPeerId)
        assertNotNull(recordBefore)
        assertEquals(testPeerName, recordBefore?.deviceName)
        assertEquals(testIpHint, recordBefore?.lastKnownIpAddress)

        // 2. Simulate complete application process termination and restart by resetting in-memory cache
        DeviceTrustManager.resetCacheForTesting(context)

        // 3. Verify persistence restored from SharedPreferences
        assertTrue("Trusted peer must persist across process restart", DeviceTrustManager.isPeerTrusted(testPeerId))
        val recordAfter = DeviceTrustManager.getTrustedPeer(testPeerId)
        assertNotNull(recordAfter)
        assertEquals(testPeerId, recordAfter?.peerDeviceId)
        assertEquals(testPeerName, recordAfter?.deviceName)
        assertEquals(testIpHint, recordAfter?.lastKnownIpAddress)
        assertTrue(recordAfter?.isTrusted == true)
    }

    @Test
    fun testRecognitionAndAutoReconnectOnAppReopen() = runTest {
        val testPeerId = "peer_test_beta_456"
        val testPeerName = "Samsung Galaxy S24"

        // 1. Record trust prior to restart
        DeviceTrustManager.recordPeerTrust(
            context = context,
            peerDeviceId = testPeerId,
            deviceName = testPeerName
        )

        // 2. Simulate app reopen: reinitialize transport
        val transport = LocalWifiTransport(context = context)
        assertTrue("Transport must recognize persisted trusted peer", transport.isKnownPeer(testPeerId))

        // 3. Discovered peer with matching device ID must be recognized as paired
        val discoveredDevice = Device(
            deviceId = testPeerId,
            deviceName = testPeerName,
            ipAddress = "192.168.1.77",
            connectionState = ConnectionState.DISCOVERED
        )
        transport.addDiscoveredDevice(discoveredDevice)

        val discoveredList = transport.discoveredDevices.value
        val recognized = discoveredList.find { it.deviceId == testPeerId }
        assertNotNull("Discovered device must be in discovered list", recognized)
        assertTrue("Discovered device matching trusted peer ID must have isPaired = true", recognized?.isPaired == true)

        transport.onNetworkLost()
    }

    @Test
    fun testIpAddressChangeWithoutBreakingPeerIdentity() = runTest {
        val testPeerId = "peer_test_gamma_789"
        val testPeerName = "MacBook Pro"
        val initialIp = "192.168.1.10"
        val newDhcpIp = "192.168.1.199"

        // 1. Peer initially trusted at initialIp
        DeviceTrustManager.recordPeerTrust(
            context = context,
            peerDeviceId = testPeerId,
            deviceName = testPeerName,
            ipHint = initialIp
        )

        val transport = LocalWifiTransport(context = context)
        val initialDevice = Device(
            deviceId = testPeerId,
            deviceName = testPeerName,
            ipAddress = initialIp,
            connectionState = ConnectionState.DISCONNECTED
        )
        transport.addDiscoveredDevice(initialDevice)

        // 2. Remote peer changes IP due to DHCP renewal or network roaming
        val updatedDeviceWithNewIp = Device(
            deviceId = testPeerId,
            deviceName = testPeerName,
            ipAddress = newDhcpIp,
            connectionState = ConnectionState.DISCONNECTED
        )
        transport.addDiscoveredDevice(updatedDeviceWithNewIp)

        // 3. Verify stable identity remains intact and IP hint is updated
        val updatedInTransport = transport.discoveredDevices.value.find { it.deviceId == testPeerId }
        assertNotNull(updatedInTransport)
        assertEquals("IP address must update to new IP", newDhcpIp, updatedInTransport?.ipAddress)
        assertTrue("Peer must remain trusted despite IP change", updatedInTransport?.isPaired == true)
        assertTrue("DeviceTrustManager must retain peer trust", DeviceTrustManager.isPeerTrusted(testPeerId))

        transport.onNetworkLost()
    }

    @Test
    fun testLogicalPeerSessionTransportLossAndRebinding() = runTest {
        val testPeerId = "peer_session_delta_321"
        val session = LogicalPeerSession(peerDeviceId = testPeerId)

        assertEquals(PeerSessionState.DISCONNECTED, session.state.value)

        // 1. Active connection
        session.transitionTo(PeerSessionState.CONNECTED)
        session.recordSuccess()
        assertEquals(1, session.successfulTransfers)
        assertEquals(1, session.totalTransfers)

        // 2. Transport loss occurs
        session.unbindTransport()
        assertEquals(PeerSessionState.DISCONNECTED, session.state.value)
        // Statistics and session identity MUST be preserved
        assertEquals(1, session.successfulTransfers)
        assertEquals(1, session.totalTransfers)

        // 3. Reconnection restores session state without resetting counters
        session.transitionTo(PeerSessionState.CONNECTED)
        session.recordSuccess()
        assertEquals(2, session.successfulTransfers)
        assertEquals(2, session.totalTransfers)
    }

    @Test
    fun testAuthorizationBoundaryEnforcement() = runTest {
        val unknownPeerId = "unknown_stranger_device_999"
        val transport = LocalWifiTransport(context = context)

        // 1. Unknown peer discovered
        val unknownDevice = Device(
            deviceId = unknownPeerId,
            deviceName = "Stranger Device",
            ipAddress = "192.168.1.88",
            connectionState = ConnectionState.DISCOVERED
        )
        transport.addDiscoveredDevice(unknownDevice)

        // 2. Verify stranger is NOT trusted and isPaired is false
        assertFalse(DeviceTrustManager.isPeerTrusted(unknownPeerId))
        assertFalse(transport.isKnownPeer(unknownPeerId))

        val inList = transport.discoveredDevices.value.find { it.deviceId == unknownPeerId }
        assertNotNull(inList)
        assertFalse("Unknown peer must NOT be marked as paired", inList?.isPaired == true)

        transport.onNetworkLost()
    }

    @Test
    fun testRevokePeerTrustRemovesPersistence() = runTest {
        val testPeerId = "peer_to_revoke_555"
        DeviceTrustManager.recordPeerTrust(
            context = context,
            peerDeviceId = testPeerId,
            deviceName = "Old Device"
        )
        assertTrue(DeviceTrustManager.isPeerTrusted(testPeerId))

        // Revoke
        val transport = LocalWifiTransport(context = context)
        transport.revokePeerAuthorization(testPeerId)

        assertFalse("Peer trust must be revoked in DeviceTrustManager", DeviceTrustManager.isPeerTrusted(testPeerId))
        assertFalse("Peer trust must be revoked in transport", transport.isKnownPeer(testPeerId))

        // Reset memory cache to verify persistence reflects revocation
        DeviceTrustManager.resetCacheForTesting(context)
        assertFalse("Revocation must persist across restarts", DeviceTrustManager.isPeerTrusted(testPeerId))

        transport.onNetworkLost()
    }
}
