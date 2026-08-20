package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ConnectionState
import com.example.data.model.Device

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    devices: List<Device>,
    discoveredDevices: List<Device> = emptyList(),
    isDiscovering: Boolean = false,
    isServerRunning: Boolean = false,
    listeningPort: Int = com.example.sync.transport.LocalWifiTransport.DEFAULT_PORT,
    incomingMessages: List<String> = emptyList(),
    lastAckResult: String? = null,
    isSendingHandshake: Boolean = false,
    onStartDiscovery: () -> Unit = {},
    onStopDiscovery: () -> Unit = {},
    onStartServer: () -> Unit = {},
    onStopServer: () -> Unit = {},
    onSendHandshake: (targetIp: String, message: String) -> Unit = { _, _ -> },
    onClearLogs: () -> Unit = {},
    onConnectDevice: (Device) -> Unit = {},
    onDisconnectDevice: (Device) -> Unit = {}
) {
    val localDevice = devices.firstOrNull { it.isLocalDevice }
    val pairedDevices = devices.filter { !it.isLocalDevice && it.isPaired }.distinctBy { it.deviceId }
    val pairedDeviceIds = pairedDevices.map { it.deviceId }.toSet()
    // Only show unassigned (unpaired) new devices in the discovered section to avoid duplication, crashes, and duplicate representations of trusted peers
    val unassignedDiscoveredDevices = discoveredDevices
        .filter { !it.isPaired && it.deviceId != localDevice?.deviceId && !pairedDeviceIds.contains(it.deviceId) }
        .distinctBy { it.deviceId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Devices & Diagnostic", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .testTag("devices_screen_list"),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Milestone 5.1 Temporary Diagnostic Card
            item(key = "diagnostic_card") {
                Milestone51DiagnosticCard(
                    isServerRunning = isServerRunning,
                    listeningPort = listeningPort,
                    incomingMessages = incomingMessages,
                    lastAckResult = lastAckResult,
                    isSendingHandshake = isSendingHandshake,
                    onStartServer = onStartServer,
                    onStopServer = onStopServer,
                    onSendHandshake = onSendHandshake,
                    onClearLogs = onClearLogs
                )
            }

            // Local Device Section
            item(key = "header_this_device") {
                Text(
                    text = "This Device",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            item(key = "local_device_card") {
                if (localDevice != null) {
                    DeviceItemCard(device = localDevice, isLocal = true)
                }
            }

            // Milestone 5.2 Discovery Control Card
            item(key = "discovery_control_card_item") {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("discovery_control_card"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Local Network Discovery (mDNS)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isDiscovering) "Status: Discovering nearby peers..." else "Status: Discovery idle",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (isDiscovering) {
                            OutlinedButton(
                                onClick = onStopDiscovery,
                                modifier = Modifier.testTag("stop_discovery_button")
                            ) {
                                Text("Stop", fontSize = 12.sp)
                            }
                        } else {
                            Button(
                                onClick = onStartDiscovery,
                                modifier = Modifier.testTag("start_discovery_button")
                            ) {
                                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Discover", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // Discovered Nearby Devices Section (Milestone 5.2)
            item(key = "header_discovered_devices") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Discovered Nearby Devices (${unassignedDiscoveredDevices.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (isDiscovering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }

            if (unassignedDiscoveredDevices.isEmpty()) {
                item(key = "discovered_empty_text") {
                    Text(
                        text = "No new un-paired devices discovered nearby. Tap 'Discover' above or ensure peer is on the same Wi-Fi.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
            } else {
                items(unassignedDiscoveredDevices, key = { "discovered_${it.deviceId}" }) { device ->
                    DiscoveredDeviceCard(
                        device = device,
                        onConnect = onConnectDevice,
                        onDisconnect = onDisconnectDevice
                    )
                }
            }

            // Paired Devices Section
            item(key = "header_paired_devices") {
                Text(
                    text = "Trusted Paired Devices (${pairedDevices.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (pairedDevices.isEmpty()) {
                item(key = "paired_empty_text") {
                    Text(
                        text = "No trusted paired devices. Discovered peers can be authorized as trusted.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
            } else {
                items(pairedDevices, key = { "paired_${it.deviceId}" }) { device ->
                    DeviceItemCard(device = device, isLocal = false)
                }
            }

            item(key = "bottom_spacer") { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun DiscoveredDeviceCard(
    device: Device,
    onConnect: (Device) -> Unit = {},
    onDisconnect: (Device) -> Unit = {}
) {
    val statusText = when (device.connectionState) {
        ConnectionState.CONNECTED -> "CONNECTED"
        ConnectionState.CONNECTING -> "CONNECTING..."
        ConnectionState.RECONNECTING -> "RECONNECTING..."
        ConnectionState.ERROR -> "ERROR"
        ConnectionState.DISCONNECTED -> "DISCONNECTED"
        ConnectionState.DISCOVERED -> if (device.isPaired) "TRUSTED PEER" else "UNKNOWN PEER"
    }

    val statusBgColor = when (device.connectionState) {
        ConnectionState.CONNECTED -> Color(0xFF2E7D32)
        ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> Color(0xFFE65100)
        ConnectionState.ERROR -> Color(0xFFC62828)
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.surfaceVariant
        ConnectionState.DISCOVERED -> if (device.isPaired) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer
    }

    val statusTextColor = when (device.connectionState) {
        ConnectionState.CONNECTED, ConnectionState.CONNECTING, ConnectionState.RECONNECTING, ConnectionState.ERROR -> Color.White
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
        ConnectionState.DISCOVERED -> if (device.isPaired) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("discovered_device_card_${device.deviceId}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Smartphone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = device.deviceName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        if (device.isPaired) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "TRUSTED PEER",
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        } else {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "UNKNOWN",
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Text(
                        text = "Device ID: ${device.deviceId}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    color = statusBgColor,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = statusText,
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                            .testTag("device_status_${device.deviceId}"),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = statusTextColor
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "IP Address:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = device.ipAddress ?: "Unknown IP",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("device_ip_${device.deviceId}")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Sync Status:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (device.connectionState == ConnectionState.CONNECTED) "Sync: Active" else if (device.connectionState == ConnectionState.RECONNECTING) "Reconnecting..." else "Inactive",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (device.connectionState == ConnectionState.CONNECTED) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(4.dp))

            // Milestone 5.3 & 5.5 Connection Control Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                when (device.connectionState) {
                    ConnectionState.CONNECTED -> {
                        OutlinedButton(
                            onClick = { onDisconnect(device) },
                            modifier = Modifier.testTag("disconnect_button_${device.deviceId}"),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.PowerOff, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("DISCONNECT", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> {
                        Button(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.testTag("connecting_button_${device.deviceId}"),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (device.connectionState == ConnectionState.RECONNECTING) "RECONNECTING..." else "CONNECTING...",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    else -> {
                        Button(
                            onClick = { onConnect(device) },
                            modifier = Modifier.testTag("connect_button_${device.deviceId}"),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("CONNECT", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceItemCard(
    device: Device,
    isLocal: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("device_card_${device.deviceId}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val isRealOnline = if (isLocal) device.isOnline else (device.connectionState == ConnectionState.CONNECTED || device.connectionState == ConnectionState.DISCOVERED)
            val statusText = when {
                isLocal -> if (device.isOnline) "● Online (This Device)" else "○ Offline (No Wi-Fi)"
                device.connectionState == ConnectionState.CONNECTED -> "● Connected"
                device.connectionState == ConnectionState.DISCOVERED -> "● Discovered (LAN)"
                device.connectionState == ConnectionState.CONNECTING -> "○ Connecting..."
                else -> "○ Offline"
            }
            val statusColor = when {
                isLocal && device.isOnline -> MaterialTheme.colorScheme.primary
                !isLocal && device.connectionState == ConnectionState.CONNECTED -> Color(0xFF4CAF50)
                !isLocal && device.connectionState == ConnectionState.DISCOVERED -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }

            Icon(
                imageVector = when (device.deviceType) {
                    "DESKTOP" -> Icons.Default.DesktopWindows
                    "LAPTOP" -> Icons.Default.Laptop
                    "TABLET" -> Icons.Default.Tablet
                    else -> Icons.Default.Smartphone
                },
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(28.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.deviceName,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = statusText,
                        fontSize = 12.sp,
                        color = statusColor
                    )
                    Text(
                        text = "• ID: ${device.deviceId}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isLocal) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "ACTIVE",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            } else if (device.isPaired) {
                OutlinedButton(
                    onClick = { /* Unpair dialog in Milestone 6 */ },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                ) {
                    Text("Trusted", fontSize = 12.sp)
                }
            } else {
                Button(
                    onClick = { /* Pairing flow in Milestone 6 */ },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                ) {
                    Text("Pair Device", fontSize = 12.sp)
                }
            }
        }
    }
}
