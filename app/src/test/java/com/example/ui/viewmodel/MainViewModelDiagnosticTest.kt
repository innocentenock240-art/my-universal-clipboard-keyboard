package com.example.ui.viewmodel

import androidx.test.core.app.ApplicationProvider
import com.example.sync.transport.LocalWifiTransport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainViewModelDiagnosticTest {

    @Test
    fun testWifiServerStartAndStopInViewModel() = runBlocking {
        val transport = LocalWifiTransport(port = 54331)
        val viewModel = MainViewModel(
            application = ApplicationProvider.getApplicationContext(),
            localWifiTransport = transport
        )

        // Ensure server is started
        viewModel.startWifiServer()
        withTimeout(5000) {
            viewModel.isWifiServerRunning.first { it }
        }
        assertTrue(viewModel.isWifiServerRunning.value)

        // Stop server and verify
        viewModel.stopWifiServer()
        withTimeout(5000) {
            viewModel.isWifiServerRunning.first { !it }
        }
        assertFalse(viewModel.isWifiServerRunning.value)
    }

    @Test
    fun testHandshakeSelfTestInViewModel() = runBlocking {
        val receiverTransport = LocalWifiTransport(port = 54332)
        receiverTransport.startServer()
        assertTrue(receiverTransport.isAvailable)

        val senderTransport = LocalWifiTransport(port = 54333)
        val senderViewModel = MainViewModel(
            application = ApplicationProvider.getApplicationContext(),
            localWifiTransport = senderTransport
        )

        val ackResponse = senderTransport.sendHandshake(
            targetIp = "127.0.0.1",
            message = "HELLO_FROM_PHONE_A",
            targetPort = 54332
        )

        assertNotNull(ackResponse)
        assertTrue("Expected ACK but got $ackResponse", ackResponse!!.startsWith("ACK_"))

        val incomingMsgs = withTimeout(5000) {
            receiverTransport.incomingMessages.first()
        }
        assertEquals("HELLO_FROM_PHONE_A", incomingMsgs)

        receiverTransport.stopServer()
        senderTransport.stopServer()
    }

    @Test
    fun testWifiDiscoveryInViewModel() = runBlocking {
        val transport = LocalWifiTransport(port = 54334)
        val viewModel = MainViewModel(
            application = ApplicationProvider.getApplicationContext(),
            localWifiTransport = transport
        )

        // Stop discovery first
        viewModel.stopWifiDiscovery()
        withTimeout(5000) {
            viewModel.isWifiDiscovering.first { !it }
        }
        assertFalse(viewModel.isWifiDiscovering.value)

        // Start discovery and verify
        viewModel.startWifiDiscovery()
        withTimeout(5000) {
            viewModel.isWifiDiscovering.first { it }
        }
        assertTrue(viewModel.isWifiDiscovering.value)
    }
}
