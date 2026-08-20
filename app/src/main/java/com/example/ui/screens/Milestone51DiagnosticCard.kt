package com.example.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.sync.transport.LocalWifiTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun Milestone51DiagnosticCard(
    isServerRunning: Boolean,
    listeningPort: Int = LocalWifiTransport.DEFAULT_PORT,
    incomingMessages: List<String>,
    lastAckResult: String?,
    isSendingHandshake: Boolean,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onSendHandshake: (targetIp: String, message: String) -> Unit,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val localIp = rememberLocalIpAddress()
    var targetIpInput by remember { mutableStateOf("") }
    var messageInput by remember { mutableStateOf("HELLO_FROM_PHONE_A") }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("diagnostic_card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(16.dp),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.BugReport,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Milestone 5.1 Transport Diagnostic",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Temporary Physical Phone A ↔ Phone B Test UI",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider()

            // Server Status & Information
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Server Status:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Surface(
                            color = if (isServerRunning) Color(0xFF2E7D32) else Color(0xFFC62828),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = if (isServerRunning) "Server: RUNNING" else "Server: STOPPED",
                                modifier = Modifier
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                                    .testTag("server_status_text"),
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Listening Port:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(text = "Port: $listeningPort", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Your Local IP:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(text = localIp, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            // Server Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onStartServer,
                    enabled = !isServerRunning,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("start_server_button")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Start Server")
                }

                OutlinedButton(
                    onClick = onStopServer,
                    enabled = isServerRunning,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("stop_server_button")
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Stop Server")
                }
            }

            HorizontalDivider()

            // Send Handshake Section
            Text(
                text = "Send Test Handshake",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = targetIpInput,
                onValueChange = { targetIpInput = it },
                label = { Text("Target Phone B IP Address") },
                placeholder = { Text("e.g. 192.168.1.50") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("target_ip_input"),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Wifi, contentDescription = null) }
            )

            OutlinedTextField(
                value = messageInput,
                onValueChange = { messageInput = it },
                label = { Text("Message Payload") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Button(
                onClick = { onSendHandshake(targetIpInput, messageInput) },
                enabled = targetIpInput.isNotBlank() && !isSendingHandshake,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("send_hello_button")
            ) {
                if (isSendingHandshake) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Sending...")
                } else {
                    Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Send HELLO")
                }
            }

            // Returned ACK Display
            if (lastAckResult != null) {
                Surface(
                    color = if (lastAckResult.startsWith("ACK_")) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "Returned ACK Result:",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (lastAckResult.startsWith("ACK_")) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = lastAckResult,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.testTag("last_ack_text"),
                            color = if (lastAckResult.startsWith("ACK_")) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            HorizontalDivider()

            // Incoming Messages Received Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Received Messages (${incomingMessages.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                if (incomingMessages.isNotEmpty()) {
                    TextButton(onClick = onClearLogs) {
                        Text("Clear Logs", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            if (incomingMessages.isEmpty()) {
                Text(
                    text = "No incoming socket messages received yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        incomingMessages.forEach { msg ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF2E7D32),
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.testTag("incoming_messages_text")
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun rememberLocalIpAddress(): String {
    val context = LocalContext.current
    var ip by remember { mutableStateOf("Detecting...") }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val detected = try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                val ipInt = wifiManager?.connectionInfo?.ipAddress ?: 0
                if (ipInt != 0) {
                    String.format(
                        "%d.%d.%d.%d",
                        ipInt and 0xff,
                        ipInt shr 8 and 0xff,
                        ipInt shr 16 and 0xff,
                        ipInt shr 24 and 0xff
                    )
                } else {
                    var foundIp: String? = null
                    val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                    while (interfaces != null && interfaces.hasMoreElements()) {
                        val ni = interfaces.nextElement()
                        val addrs = ni.inetAddresses
                        while (addrs.hasMoreElements()) {
                            val addr = addrs.nextElement()
                            if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                                foundIp = addr.hostAddress
                                break
                            }
                        }
                        if (foundIp != null) break
                    }
                    foundIp ?: "127.0.0.1"
                }
            } catch (e: Exception) {
                "127.0.0.1"
            }
            ip = detected
        }
    }
    return ip
}
