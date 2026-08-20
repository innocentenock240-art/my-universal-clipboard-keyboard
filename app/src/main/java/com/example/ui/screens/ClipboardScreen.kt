package com.example.ui.screens

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.policy.SendDestination
import com.example.data.model.ClipboardItem
import com.example.data.model.Device

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipboardScreen(
    items: List<ClipboardItem>,
    isCaptureActive: Boolean = true,
    isAutoSyncEnabled: Boolean = true,
    isSyncPaused: Boolean = false,
    devices: List<Device> = emptyList(),
    onAddItem: (String) -> Unit,
    onAddRichItem: (type: String, content: String, mimeType: String, fileName: String?, sizeBytes: Long) -> Unit = { _, _, _, _, _ -> },
    onCopyItem: (String) -> Unit,
    onCopyClipboardItem: (ClipboardItem) -> Unit = { onCopyItem(it.content) },
    onToggleFavorite: (String) -> Unit,
    onTogglePin: (String) -> Unit,
    onDeleteItem: (String) -> Unit,
    onDeleteItems: (List<String>) -> Unit = {},
    onSyncItem: (ClipboardItem) -> Unit = {},
    onSendItems: (List<ClipboardItem>, SendDestination) -> Unit = { _, _ -> },
    onClearAll: () -> Unit = {},
    onCheckClipboard: () -> Unit = {},
    onToggleCapture: () -> Unit = {},
    onToggleAutoSync: () -> Unit = {},
    onTogglePauseSync: () -> Unit = {}
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilterTab by remember { mutableStateOf(0) } // 0: All, 1: Local, 2: Synced, 3: Favorites, 4: Pinned
    var showAddDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showSendDestinationDialog by remember { mutableStateOf(false) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedItemIds by remember { mutableStateOf(setOf<String>()) }
    var previewImageItem by remember { mutableStateOf<ClipboardItem?>(null) }

    // Check system clipboard upon entering this screen
    LaunchedEffect(Unit) {
        onCheckClipboard()
    }

    val filteredItems = remember(items, searchQuery, selectedFilterTab) {
        items.filter { item ->
            val matchesSearch = item.content.contains(searchQuery, ignoreCase = true) ||
                    item.sourceDeviceName.contains(searchQuery, ignoreCase = true) ||
                    (item.fileName?.contains(searchQuery, ignoreCase = true) == true) ||
                    item.type.contains(searchQuery, ignoreCase = true)
            val matchesTab = when (selectedFilterTab) {
                1 -> item.sourceDeviceId.startsWith("dev_local") || item.sourceDeviceId.isBlank() // Local Only
                2 -> !item.sourceDeviceId.startsWith("dev_local") && item.sourceDeviceId.isNotBlank() // Synced
                3 -> item.isFavorite
                4 -> item.isPinned
                else -> true
            }
            matchesSearch && matchesTab
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSelectionMode) {
                        Text("Selected (${selectedItemIds.size})", fontWeight = FontWeight.Bold)
                    } else {
                        Text("Clipboard History", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        IconButton(onClick = {
                            selectedItemIds = items.map { it.id }.toSet()
                        }) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Select All")
                        }
                        IconButton(
                            onClick = {
                                if (selectedItemIds.isNotEmpty()) {
                                    showSendDestinationDialog = true
                                }
                            },
                            enabled = selectedItemIds.isNotEmpty()
                        ) {
                            Icon(Icons.Default.Send, contentDescription = "Send Selected", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = {
                            if (selectedItemIds.isNotEmpty()) {
                                onDeleteItems(selectedItemIds.toList())
                                isSelectionMode = false
                                selectedItemIds = emptySet()
                            }
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Selected", tint = MaterialTheme.colorScheme.error)
                        }
                        IconButton(onClick = {
                            isSelectionMode = false
                            selectedItemIds = emptySet()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel Selection")
                        }
                    } else {
                        IconButton(onClick = onCheckClipboard) {
                            Icon(
                                imageVector = Icons.Outlined.Sync,
                                contentDescription = "Check System Clipboard",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (items.isNotEmpty()) {
                            IconButton(onClick = { isSelectionMode = true }) {
                                Icon(Icons.Default.CheckCircle, contentDescription = "Selection Mode")
                            }
                            IconButton(onClick = { showClearDialog = true }) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteSweep,
                                    contentDescription = "Clear All History",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            if (!isSelectionMode) {
                FloatingActionButton(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.testTag("add_clipboard_fab"),
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Clipboard Item",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Universal Synchronization & Capture Policy Controls
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isSyncPaused)
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                    else if (isAutoSyncEnabled)
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    else
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isSyncPaused) "Universal Sync: PAUSED"
                                else if (isAutoSyncEnabled) "Universal Sync: AUTO (Active)"
                                else "Universal Sync: LOCAL ONLY (Manual)",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (isSyncPaused) "Sync is paused. Items stay on this device."
                                else if (isAutoSyncEnabled) "Copies sync to connected trusted peers."
                                else "Auto-sync disabled. Tap 'Sync' on any item to send.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedButton(
                                onClick = onTogglePauseSync,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(if (isSyncPaused) "Resume" else "Pause", fontSize = 11.sp)
                            }
                            FilledTonalButton(
                                onClick = onToggleAutoSync,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(if (isAutoSyncEnabled) "Auto ON" else "Auto OFF", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("search_clipboard_input"),
                placeholder = { Text("Search text, URLs, code, files...") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search"
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // Filter Tabs (All, Local, Synced, Favorites, Pinned)
            ScrollableTabRow(
                selectedTabIndex = selectedFilterTab,
                modifier = Modifier.fillMaxWidth(),
                edgePadding = 0.dp
            ) {
                Tab(
                    selected = selectedFilterTab == 0,
                    onClick = { selectedFilterTab = 0 },
                    text = { Text("All (${items.size})") }
                )
                Tab(
                    selected = selectedFilterTab == 1,
                    onClick = { selectedFilterTab = 1 },
                    text = { Text("Local (${items.count { it.sourceDeviceId.startsWith("dev_local") || it.sourceDeviceId.isBlank() }})") }
                )
                Tab(
                    selected = selectedFilterTab == 2,
                    onClick = { selectedFilterTab = 2 },
                    text = { Text("Synced (${items.count { !it.sourceDeviceId.startsWith("dev_local") && it.sourceDeviceId.isNotBlank() }})") }
                )
                Tab(
                    selected = selectedFilterTab == 3,
                    onClick = { selectedFilterTab = 3 },
                    text = { Text("Favorites (${items.count { it.isFavorite }})") }
                )
                Tab(
                    selected = selectedFilterTab == 4,
                    onClick = { selectedFilterTab = 4 },
                    text = { Text("Pinned (${items.count { it.isPinned }})") }
                )
            }

            // List of items
            if (filteredItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (items.isEmpty()) "No clipboard items yet." else "No matching clipboard items found",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredItems, key = { it.id }) { item ->
                        val isSelected = selectedItemIds.contains(item.id)
                        ClipboardItemCard(
                            item = item,
                            isSelectionMode = isSelectionMode,
                            isSelected = isSelected,
                            onSelect = {
                                selectedItemIds = if (isSelected) selectedItemIds - item.id else selectedItemIds + item.id
                            },
                            onCopy = { onCopyClipboardItem(item) },
                            onToggleFavorite = { onToggleFavorite(item.id) },
                            onTogglePin = { onTogglePin(item.id) },
                            onDelete = { onDeleteItem(item.id) },
                            onSync = { onSyncItem(item) },
                            onPreviewImage = { previewImageItem = item }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }
    }

    if (showSendDestinationDialog) {
        val selectedItemsList = items.filter { selectedItemIds.contains(it.id) }
        SelectDestinationDialog(
            selectedCount = selectedItemsList.size,
            devices = devices,
            onDismiss = { showSendDestinationDialog = false },
            onSelectDestination = { destination ->
                onSendItems(selectedItemsList, destination)
                showSendDestinationDialog = false
                isSelectionMode = false
                selectedItemIds = emptySet()
            }
        )
    }

    if (showAddDialog) {
        AddRichClipboardItemDialog(
            onDismiss = { showAddDialog = false },
            onConfirmText = { text ->
                onAddItem(text)
                showAddDialog = false
            },
            onConfirmRich = { type, content, mimeType, fileName, sizeBytes ->
                onAddRichItem(type, content, mimeType, fileName, sizeBytes)
                showAddDialog = false
            }
        )
    }

    if (previewImageItem != null) {
        val item = previewImageItem!!
        ImagePreviewDialog(
            item = item,
            onDismiss = { previewImageItem = null },
            onCopy = {
                onCopyClipboardItem(item)
                previewImageItem = null
            },
            onSync = {
                onSyncItem(item)
                previewImageItem = null
            }
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear All Clipboard History") },
            text = { Text("Are you sure you want to delete all saved items from your local database?") },
            confirmButton = {
                Button(
                    onClick = {
                        onClearAll()
                        showClearDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ClipboardItemCard(
    item: ClipboardItem,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onSelect: () -> Unit = {},
    onCopy: () -> Unit,
    onToggleFavorite: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    onSync: () -> Unit = {},
    onPreviewImage: () -> Unit = {}
) {
    val isLocal = item.sourceDeviceId.startsWith("dev_local") || item.sourceDeviceId.isBlank()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("item_card_${item.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header Row: Type Badge + Device Name + Selection/Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isSelectionMode) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onSelect() }
                        )
                    }

                    SuggestionChip(
                        onClick = {},
                        label = { Text(item.type, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        icon = {
                            Icon(
                                imageVector = when (item.type) {
                                    ClipboardItem.TYPE_URL -> Icons.Default.Link
                                    ClipboardItem.TYPE_CODE -> Icons.Default.Code
                                    ClipboardItem.TYPE_HTML -> Icons.Default.Html
                                    ClipboardItem.TYPE_IMAGE -> Icons.Default.Image
                                    ClipboardItem.TYPE_FILE -> Icons.Default.AttachFile
                                    else -> Icons.Default.TextFields
                                },
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                            labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )

                    Surface(
                        color = if (isLocal) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = if (isLocal) "LOCAL" else "SYNCED",
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            color = if (isLocal) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }

                    Text(
                        text = item.sourceDeviceName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row {
                    IconButton(onClick = onToggleFavorite, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = if (item.isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                            contentDescription = "Favorite",
                            tint = if (item.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onTogglePin, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = if (item.isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                            contentDescription = "Pin",
                            tint = if (item.isPinned) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Content Body Based on Type
            when (item.type) {
                ClipboardItem.TYPE_IMAGE -> {
                    val bitmap = remember(item.content) {
                        try {
                            val decodedBytes = Base64.decode(item.content, Base64.DEFAULT)
                            if (decodedBytes.isEmpty() || decodedBytes.size > 10 * 1024 * 1024) {
                                null
                            } else {
                                val boundsOptions = BitmapFactory.Options().apply {
                                    inJustDecodeBounds = true
                                }
                                BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size, boundsOptions)

                                val maxDimension = 1024
                                var sampleSize = 1
                                if (boundsOptions.outHeight > maxDimension || boundsOptions.outWidth > maxDimension) {
                                    val halfHeight = boundsOptions.outHeight / 2
                                    val halfWidth = boundsOptions.outWidth / 2
                                    while ((halfHeight / sampleSize) >= maxDimension && (halfWidth / sampleSize) >= maxDimension) {
                                        sampleSize *= 2
                                    }
                                }

                                val decodeOptions = BitmapFactory.Options().apply {
                                    inSampleSize = sampleSize
                                    inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                                }
                                BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size, decodeOptions)
                            }
                        } catch (t: Throwable) {
                            null
                        }
                    }

                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = item.fileName ?: "Clipboard Image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { onPreviewImage() },
                            contentScale = ContentScale.Fit
                        )
                    }
                    if (item.fileName != null) {
                        Text(
                            text = item.fileName,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                ClipboardItem.TYPE_FILE -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.InsertDriveFile,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                            Column {
                                Text(
                                    text = item.fileName ?: "Attached File",
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "${item.mimeType} • ${item.displaySize}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                ClipboardItem.TYPE_CODE -> {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = item.content,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(10.dp),
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                else -> {
                    Text(
                        text = item.content,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Footer Row: Metadata & Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${item.displaySize} • SHA-256: ${item.hash.take(8)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                    OutlinedButton(
                        onClick = onSync,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CloudUpload,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "Sync", fontSize = 11.sp)
                    }
                    Button(
                        onClick = onCopy,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "Copy", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun AddRichClipboardItemDialog(
    onDismiss: () -> Unit,
    onConfirmText: (String) -> Unit,
    onConfirmRich: (type: String, content: String, mimeType: String, fileName: String?, sizeBytes: Long) -> Unit
) {
    var selectedTypeIndex by remember { mutableStateOf(0) } // 0: Text, 1: URL, 2: Code, 3: HTML, 4: Image, 5: File
    var textInput by remember { mutableStateOf("") }
    var fileNameInput by remember { mutableStateOf("") }

    val typeOptions = listOf("Text", "URL", "Code", "HTML", "Image", "File")

    // Prepopulate sensible template when switching tabs
    LaunchedEffect(selectedTypeIndex) {
        when (selectedTypeIndex) {
            0 -> if (textInput.isBlank()) textInput = "Hello Universal Clipboard! 🚀"
            1 -> if (textInput.isBlank()) textInput = "https://example.com/api?user=alpha#dashboard"
            2 -> if (textInput.isBlank()) textInput = "fun synchronizeClipboard(item: ClipboardItem) {\n    syncEngine.broadcast(item)\n}"
            3 -> if (textInput.isBlank()) textInput = "<div style=\"color: #0066cc;\"><h1>Universal Clipboard</h1><p>Rich HTML content</p></div>"
            4 -> {
                fileNameInput = "sample_badge.png"
                // 1x1 transparent PNG Base64 for instant testing
                textInput = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkWPjfDwAEeQHzE1LqtwAAAABJRU5ErkJggg=="
            }
            5 -> {
                fileNameInput = "document.pdf"
                textInput = "JVBERi0xLjQKJeLjz9MKMSAwIG9iaiA8PC9UeXBlL0NhdGFsb2cvUGFnZXMgMiAwIFI+PmVuZG9iagoyIDAgb2JqIDw8L1R5cGUvUGFnZXMvS2lkc1szIDAgUl0vQ291bnQgMT4+ZW5kb2JqCjMgMCBvYmo8PC9UeXBlL1BhZ2UvUGFyZW50IDIgMCBSL01lZGlhQm94WzAgMCA2MTIgNzkyXT4+ZW5kb2JqCnhyZWYKMCA0Cg=="
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Rich Clipboard Item") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Type selector chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    typeOptions.forEachIndexed { index, title ->
                        FilterChip(
                            selected = selectedTypeIndex == index,
                            onClick = {
                                selectedTypeIndex = index
                                textInput = ""
                            },
                            label = { Text(title, fontSize = 12.sp) }
                        )
                    }
                }

                if (selectedTypeIndex == 4 || selectedTypeIndex == 5) {
                    OutlinedTextField(
                        value = fileNameInput,
                        onValueChange = { fileNameInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("File Name") },
                        singleLine = true
                    )
                }

                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp),
                    label = {
                        Text(
                            when (selectedTypeIndex) {
                                1 -> "URL Link"
                                2 -> "Source Code Snippet"
                                3 -> "HTML Content"
                                4 -> "Image Payload (Base64)"
                                5 -> "File Payload (Base64/Text)"
                                else -> "Text or Unicode Content"
                            }
                        )
                    }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when (selectedTypeIndex) {
                        0, 1 -> onConfirmText(textInput)
                        2 -> onConfirmRich(ClipboardItem.TYPE_CODE, textInput, ClipboardItem.MIME_TEXT_PLAIN, null, textInput.toByteArray(Charsets.UTF_8).size.toLong())
                        3 -> onConfirmRich(ClipboardItem.TYPE_HTML, textInput, ClipboardItem.MIME_TEXT_HTML, null, textInput.toByteArray(Charsets.UTF_8).size.toLong())
                        4 -> onConfirmRich(ClipboardItem.TYPE_IMAGE, textInput, ClipboardItem.MIME_IMAGE_PNG, fileNameInput.ifBlank { "image.png" }, textInput.toByteArray(Charsets.UTF_8).size.toLong())
                        5 -> onConfirmRich(ClipboardItem.TYPE_FILE, textInput, ClipboardItem.MIME_OCTET_STREAM, fileNameInput.ifBlank { "file.dat" }, textInput.toByteArray(Charsets.UTF_8).size.toLong())
                    }
                },
                enabled = textInput.isNotBlank()
            ) {
                Text("Add & Sync")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun SelectDestinationDialog(
    selectedCount: Int,
    devices: List<Device>,
    onDismiss: () -> Unit,
    onSelectDestination: (SendDestination) -> Unit
) {
    val nonLocalDevices = devices.filter { !it.isLocalDevice }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Send $selectedCount Item${if (selectedCount > 1) "s" else ""}", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Choose destination for this transfer bundle:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Option 1: All Trusted Devices
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onSelectDestination(SendDestination.AllTrustedPeers) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column {
                            Text("All Trusted Devices", fontWeight = FontWeight.SemiBold)
                            Text("Send to all connected trusted peers", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                if (nonLocalDevices.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text("Or select specific peer:", style = MaterialTheme.typography.labelMedium)
                    nonLocalDevices.forEach { dev ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onSelectDestination(SendDestination.SpecificPeer(dev.deviceId)) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = when (dev.deviceType) {
                                        "DESKTOP" -> Icons.Default.DesktopWindows
                                        "LAPTOP" -> Icons.Default.Laptop
                                        "TABLET" -> Icons.Default.Tablet
                                        else -> Icons.Default.Smartphone
                                    },
                                    contentDescription = null
                                )
                                Column {
                                    Text(dev.deviceName, fontWeight = FontWeight.Medium)
                                    Text(
                                        if (dev.connectionState == com.example.data.model.ConnectionState.CONNECTED) "Connected" else "Offline / Saved",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (dev.connectionState == com.example.data.model.ConnectionState.CONNECTED) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun ImagePreviewDialog(
    item: ClipboardItem,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onSync: () -> Unit
) {
    val bitmap = remember(item.content) {
        try {
            val decodedBytes = Base64.decode(item.content, Base64.DEFAULT)
            if (decodedBytes.isEmpty()) null
            else BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
        } catch (_: Throwable) {
            null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Image, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    text = item.fileName ?: "Image Preview",
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = item.fileName ?: "Full Image Preview",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Image cannot be rendered directly", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Type: ${item.mimeType}", style = MaterialTheme.typography.bodySmall)
                        Text("Size: ${item.displaySize}", style = MaterialTheme.typography.bodySmall)
                        Text("Device: ${item.sourceDeviceName}", style = MaterialTheme.typography.bodySmall)
                        Text("SHA-256: ${item.hash.take(16)}...", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCopy) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Copy")
                }
                Button(onClick = onSync) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Sync")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}



