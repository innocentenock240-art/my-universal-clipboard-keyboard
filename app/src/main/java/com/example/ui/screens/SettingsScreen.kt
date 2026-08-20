package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    retentionDays: Int,
    isWifiSyncEnabled: Boolean,
    isBluetoothSyncEnabled: Boolean,
    isWifiDirectSyncEnabled: Boolean = true,
    isCloudSyncEnabled: Boolean,
    syncPolicy: com.example.core.policy.SyncPolicy = com.example.core.policy.SyncPolicy(),
    onRetentionDaysChanged: (Int) -> Unit,
    onWifiSyncToggled: (Boolean) -> Unit,
    onBluetoothSyncToggled: (Boolean) -> Unit = {},
    onWifiDirectSyncToggled: (Boolean) -> Unit = {},
    onToggleAutoSync: () -> Unit = {},
    onTogglePauseSync: () -> Unit = {},
    onSetSyncScope: (com.example.core.policy.SyncScope) -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
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
            // Synchronization Policy & Scope
            item {
                Text(
                    text = "Synchronization Policy",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Auto-sync Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Sync,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Column {
                                    Text(text = "Automatic Sync", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        text = "Sync newly copied items immediately in background",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(
                                checked = syncPolicy.isAutoSyncEnabled,
                                onCheckedChange = { onToggleAutoSync() },
                                modifier = Modifier.testTag("setting_auto_sync_switch")
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                        // Pause Sync Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = if (syncPolicy.isSyncPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = null,
                                    tint = if (syncPolicy.isSyncPaused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                )
                                Column {
                                    Text(
                                        text = if (syncPolicy.isSyncPaused) "Sync is Paused" else "Pause Sync",
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (syncPolicy.isSyncPaused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Temporarily suspend all automatic clipboard synchronization",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(
                                checked = syncPolicy.isSyncPaused,
                                onCheckedChange = { onTogglePauseSync() },
                                modifier = Modifier.testTag("setting_pause_sync_switch")
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                        // Default Scope Selector
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Default Sync Scope",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = syncPolicy.defaultScope == com.example.core.policy.SyncScope.AUTO,
                                    onClick = { onSetSyncScope(com.example.core.policy.SyncScope.AUTO) },
                                    label = { Text("Auto") },
                                    leadingIcon = if (syncPolicy.defaultScope == com.example.core.policy.SyncScope.AUTO) {
                                        { Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    } else null,
                                    modifier = Modifier.weight(1f)
                                )
                                FilterChip(
                                    selected = syncPolicy.defaultScope == com.example.core.policy.SyncScope.SYNC_ALL,
                                    onClick = { onSetSyncScope(com.example.core.policy.SyncScope.SYNC_ALL) },
                                    label = { Text("All Peers") },
                                    leadingIcon = if (syncPolicy.defaultScope == com.example.core.policy.SyncScope.SYNC_ALL) {
                                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    } else null,
                                    modifier = Modifier.weight(1f)
                                )
                                FilterChip(
                                    selected = syncPolicy.defaultScope == com.example.core.policy.SyncScope.LOCAL_ONLY,
                                    onClick = { onSetSyncScope(com.example.core.policy.SyncScope.LOCAL_ONLY) },
                                    label = { Text("Local Only") },
                                    leadingIcon = if (syncPolicy.defaultScope == com.example.core.policy.SyncScope.LOCAL_ONLY) {
                                        { Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    } else null,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Text(
                                text = when (syncPolicy.defaultScope) {
                                    com.example.core.policy.SyncScope.AUTO -> "Intelligent synchronization across all active authorized peers."
                                    com.example.core.policy.SyncScope.SYNC_ALL -> "All authorized peers will receive synchronized items."
                                    com.example.core.policy.SyncScope.SYNC_TARGET -> "Items are only sent when a specific peer is targeted."
                                    com.example.core.policy.SyncScope.LOCAL_ONLY -> "All items remain strictly on this device (no network transmission)."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            // Retention Section
            item {
                Text(
                    text = "Clipboard Retention",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Timer,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text(
                                    text = "Auto-Delete Expired Items",
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Standard retention: 7 Days",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "7 Days Retention Active (Items older than 7 days are automatically pruned at startup)",
                                modifier = Modifier.padding(8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Sync Transports Section
            item {
                Text(
                    text = "Allowed Sync Transports",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Wi-Fi Local Network Sync
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Wifi,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(text = "Local Wi-Fi Network", fontWeight = FontWeight.SemiBold)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = "PRIMARY",
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                    Text(
                                        text = "High-speed local LAN synchronization over TCP & mDNS",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(
                                checked = isWifiSyncEnabled,
                                onCheckedChange = onWifiSyncToggled,
                                modifier = Modifier.testTag("wifi_sync_switch")
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                        // Bluetooth Transport (Active / Allowed)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bluetooth,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(text = "Bluetooth Classic / BLE", fontWeight = FontWeight.SemiBold)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = "ALLOWED",
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                        }
                                    }
                                    Text(
                                        text = "Secondary transport for small/medium payloads and off-grid discovery",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(
                                checked = isBluetoothSyncEnabled,
                                onCheckedChange = onBluetoothSyncToggled,
                                modifier = Modifier.testTag("bluetooth_sync_switch")
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                        // Wi-Fi Direct (P2P) Transport (Active / Allowed)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.NetworkCheck,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(text = "Wi-Fi Direct (P2P)", fontWeight = FontWeight.SemiBold)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = "ALLOWED",
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                        }
                                    }
                                    Text(
                                        text = "Direct high-throughput transfers without a shared router or AP",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(
                                checked = isWifiDirectSyncEnabled,
                                onCheckedChange = onWifiDirectSyncToggled,
                                modifier = Modifier.testTag("wifi_direct_sync_switch")
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                        // USB / Wired IP Network Transport (Allowed / Active)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Usb,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(text = "USB / Wired IP Network", fontWeight = FontWeight.SemiBold)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = "ALLOWED",
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                        }
                                    }
                                    Text(
                                        text = "Direct TCP loopback & Ethernet networking for ultra-low latency",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(
                                checked = true,
                                onCheckedChange = {},
                                enabled = false
                            )
                        }
                    }
                }
            }

            // Custom Keyboard (IME) Integration Card
            item {
                Text(
                    text = "Companion Keyboard (IME)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Keyboard,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Android Keyboard Integration",
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "READY",
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                        Text(
                            text = "Universal Clipboard IME is implemented and available. Enable 'Universal Clipboard' in Android System Settings > System > Languages & Input to insert synchronized clipboard items directly in any app.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}
