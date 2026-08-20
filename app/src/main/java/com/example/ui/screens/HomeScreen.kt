package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.policy.OperationalSyncState
import com.example.data.model.ClipboardItem
import com.example.data.model.ConnectionState
import com.example.data.model.Device

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    clipboardItems: List<ClipboardItem>,
    devices: List<Device>,
    operationalSyncState: OperationalSyncState = OperationalSyncState.LOCAL_ONLY,
    isWifiAvailable: Boolean = true,
    onCopyItem: (String) -> Unit,
    onNavigateToClipboard: () -> Unit,
    onNavigateToDevices: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = "Universal Clipboard",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Universal Clipboard",
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Connection Status Banner
            item {
                val connectedDeviceCount = devices.count { !it.isLocalDevice && it.connectionState == ConnectionState.CONNECTED }
                ConnectionStatusCard(
                    operationalSyncState = operationalSyncState,
                    isWifiAvailable = isWifiAvailable,
                    connectedDeviceCount = connectedDeviceCount
                )
            }

            // Overview Metrics Row
            item {
                MetricsRow(
                    itemCount = clipboardItems.size,
                    pairedDeviceCount = devices.count { it.isPaired },
                    onNavigateToClipboard = onNavigateToClipboard,
                    onNavigateToDevices = onNavigateToDevices
                )
            }

            // Local Device Header
            item {
                val localDev = devices.firstOrNull { it.isLocalDevice }
                if (localDev != null) {
                    LocalDeviceCard(localDev)
                }
            }

            // Recent Clipboard Items Section
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recent Clipboard Items",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = onNavigateToClipboard) {
                        Text("View All (${clipboardItems.size})")
                    }
                }
            }

            if (clipboardItems.isEmpty()) {
                item {
                    EmptyStateCard()
                }
            } else {
                items(clipboardItems.take(3), key = { it.id }) { item ->
                    RecentClipboardItemCard(
                        item = item,
                        onCopy = { onCopyItem(item.content) }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun ConnectionStatusCard(
    operationalSyncState: OperationalSyncState = OperationalSyncState.LOCAL_ONLY,
    isWifiAvailable: Boolean = true,
    connectedDeviceCount: Int = 0
) {
    val (statusTitle, statusSubtitle, statusColor, statusIcon) = when {
        !isWifiAvailable || operationalSyncState == OperationalSyncState.OFFLINE -> Quadruple(
            "Wi-Fi Disconnected",
            "Connect to Wi-Fi to discover nearby devices and sync",
            MaterialTheme.colorScheme.error,
            Icons.Default.WifiOff
        )
        operationalSyncState == OperationalSyncState.PAUSED -> Quadruple(
            "Sync Paused",
            "Synchronization paused in policy. Outbound and inbound sync blocked.",
            MaterialTheme.colorScheme.secondary,
            Icons.Default.PauseCircle
        )
        operationalSyncState == OperationalSyncState.SYNCING -> Quadruple(
            "Sync Active ($connectedDeviceCount Connected)",
            "Real-time peer-to-peer clipboard synchronization active",
            Color(0xFF4CAF50),
            Icons.Default.Wifi
        )
        connectedDeviceCount > 0 -> Quadruple(
            "Ready (Manual Send)",
            "$connectedDeviceCount connected peer(s). Ready for explicit send.",
            MaterialTheme.colorScheme.primary,
            Icons.Default.Wifi
        )
        else -> Quadruple(
            "Local Only (Wi-Fi Ready)",
            "Wi-Fi available. Ready to discover and pair with nearby devices.",
            MaterialTheme.colorScheme.primary,
            Icons.Default.Wifi
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("connection_status_card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = statusTitle,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = statusSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = statusIcon,
                contentDescription = "Wi-Fi Status",
                tint = statusColor
            )
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@Composable
fun MetricsRow(
    itemCount: Int,
    pairedDeviceCount: Int,
    onNavigateToClipboard: () -> Unit,
    onNavigateToDevices: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            onClick = onNavigateToClipboard,
            modifier = Modifier
                .weight(1f)
                .testTag("metric_clipboard_card"),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "$itemCount Items",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Text(
                    text = "In History",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Card(
            onClick = onNavigateToDevices,
            modifier = Modifier
                .weight(1f)
                .testTag("metric_devices_card"),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Icon(
                    imageVector = Icons.Default.Devices,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "$pairedDeviceCount Devices",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Text(
                    text = "Paired Nearby",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun LocalDeviceCard(device: Device) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Smartphone,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.deviceName,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Text(
                    text = "Device ID: ${device.deviceId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "THIS PHONE",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun RecentClipboardItemCard(
    item: ClipboardItem,
    onCopy: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("recent_item_${item.id}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (item.type == "URL") Icons.Default.Link else Icons.Default.TextFields,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = item.sourceDeviceName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onCopy,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy content",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Text(
                text = item.content,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun EmptyStateCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ContentPasteOff,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "No clipboard items yet",
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
