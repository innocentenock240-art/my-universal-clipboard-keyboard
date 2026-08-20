package com.example.keyboard

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Html
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.content.SmartContentClassifier
import com.example.data.model.ClipboardItem
import com.example.data.model.Device
import com.example.ui.screens.ImagePreviewDialog
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Universal Toolbar View Modes
 */
enum class ImeViewMode {
    QWERTY,
    SYMBOLS,
    CLIPBOARD_HISTORY,
    EMOJI_PICKER,
    EDITING_TOOLS,
    DEVICES_PANEL,
    DIAGNOSTICS_PANEL
}

/**
 * Global Sync Status for toolbar indicator
 */
enum class ImeSyncStatus(val label: String, val dotColor: Color) {
    ACTIVE("Sync Active", Color(0xFF4CAF50)),
    LOCAL_ONLY("Local Only", Color(0xFF9E9E9E)),
    RECONNECTING("Reconnecting", Color(0xFFFFB300)),
    PAUSED("Sync Paused", Color(0xFFFF7043)),
    OFFLINE("Offline", Color(0xFFE53935))
}

@Composable
fun KeyboardScreen(
    clipboardItems: List<ClipboardItem>,
    onInsertText: (String) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    onDeleteItem: ((String) -> Unit)? = null,
    onDeleteItems: ((List<String>) -> Unit)? = null,
    onTogglePin: ((String) -> Unit)? = null,
    onToggleFavorite: ((String) -> Unit)? = null,
    onCopyItemToClipboard: ((ClipboardItem) -> Unit)? = null,
    onSendItemToDevice: ((ClipboardItem, String) -> Unit)? = null,
    onMoveCursorLeft: (() -> Unit)? = null,
    onMoveCursorRight: (() -> Unit)? = null,
    onSelectAll: (() -> Unit)? = null,
    onCut: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    onPaste: (() -> Unit)? = null,
    devices: List<Device> = emptyList(),
    syncStatus: ImeSyncStatus = ImeSyncStatus.ACTIVE,
    onToggleSyncMode: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var viewMode by remember { mutableStateOf(ImeViewMode.QWERTY) }
    var isShiftActive by remember { mutableStateOf(false) }
    var isCapsLockActive by remember { mutableStateOf(false) }

    // Clipboard search and filter state
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategoryIndex by remember { mutableIntStateOf(0) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedItemIds by remember { mutableStateOf(setOf<String>()) }
    var revealedSensitiveItemIds by remember { mutableStateOf(setOf<String>()) }
    var itemToSendToDevice by remember { mutableStateOf<ClipboardItem?>(null) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("universal_keyboard_screen"),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f),
        tonalElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 1. UNIVERSAL CLIPBOARD TOOLBAR
            UniversalImeToolbar(
                currentMode = viewMode,
                syncStatus = syncStatus,
                historyCount = clipboardItems.size,
                isSelectionMode = isSelectionMode,
                selectedCount = selectedItemIds.size,
                onModeChange = { newMode ->
                    viewMode = if (viewMode == newMode && newMode != ImeViewMode.QWERTY && newMode != ImeViewMode.SYMBOLS) {
                        ImeViewMode.QWERTY
                    } else {
                        newMode
                    }
                    if (viewMode == ImeViewMode.QWERTY) {
                        isSelectionMode = false
                        selectedItemIds = emptySet()
                    }
                },
                onDeleteSelected = {
                    if (selectedItemIds.isNotEmpty()) {
                        onDeleteItems?.invoke(selectedItemIds.toList())
                        isSelectionMode = false
                        selectedItemIds = emptySet()
                    }
                },
                onCancelSelection = {
                    isSelectionMode = false
                    selectedItemIds = emptySet()
                },
                onSelectAll = {
                    selectedItemIds = clipboardItems.map { it.id }.toSet()
                },
                onToggleSyncMode = onToggleSyncMode
            )

            // 2. ACTIVE VIEW BODY
            when (viewMode) {
                ImeViewMode.QWERTY -> {
                    QwertyKeyboardLayout(
                        isShiftActive = isShiftActive || isCapsLockActive,
                        isCapsLockActive = isCapsLockActive,
                        onToggleShift = {
                            if (isShiftActive && !isCapsLockActive) {
                                isCapsLockActive = true
                            } else if (isCapsLockActive) {
                                isCapsLockActive = false
                                isShiftActive = false
                            } else {
                                isShiftActive = true
                            }
                        },
                        onKeyClick = { char ->
                            val textToInsert = if (isShiftActive || isCapsLockActive) char.uppercase() else char
                            onInsertText(textToInsert)
                            if (isShiftActive && !isCapsLockActive) {
                                isShiftActive = false
                            }
                        },
                        onSwitchToSymbols = { viewMode = ImeViewMode.SYMBOLS },
                        onSpace = { onInsertText(" ") },
                        onBackspace = onBackspace,
                        onEnter = onEnter
                    )
                }

                ImeViewMode.SYMBOLS -> {
                    SymbolsKeyboardLayout(
                        onKeyClick = { char -> onInsertText(char) },
                        onSwitchToQwerty = { viewMode = ImeViewMode.QWERTY },
                        onSpace = { onInsertText(" ") },
                        onBackspace = onBackspace,
                        onEnter = onEnter
                    )
                }

                ImeViewMode.CLIPBOARD_HISTORY -> {
                    if (itemToSendToDevice != null) {
                        TargetDevicePickerPanel(
                            item = itemToSendToDevice!!,
                            devices = devices,
                            onSelectTargetDevice = { targetDevId ->
                                val item = itemToSendToDevice
                                if (item != null) {
                                    onSendItemToDevice?.invoke(item, targetDevId)
                                }
                                itemToSendToDevice = null
                            },
                            onCancel = {
                                itemToSendToDevice = null
                            }
                        )
                    } else {
                        ClipboardHistoryPanel(
                            clipboardItems = clipboardItems,
                            searchQuery = searchQuery,
                            onSearchQueryChange = { searchQuery = it },
                            selectedCategoryIndex = selectedCategoryIndex,
                            onCategoryIndexChange = { selectedCategoryIndex = it },
                            isSelectionMode = isSelectionMode,
                            selectedItemIds = selectedItemIds,
                            revealedSensitiveItemIds = revealedSensitiveItemIds,
                            onToggleRevealSensitive = { id ->
                                revealedSensitiveItemIds = if (revealedSensitiveItemIds.contains(id)) {
                                    revealedSensitiveItemIds - id
                                } else {
                                    revealedSensitiveItemIds + id
                                }
                            },
                            onItemClick = { item ->
                                if (isSelectionMode) {
                                    selectedItemIds = if (selectedItemIds.contains(item.id)) {
                                        selectedItemIds - item.id
                                    } else {
                                        selectedItemIds + item.id
                                    }
                                } else {
                                    onInsertText(item.content)
                                }
                            },
                            onItemLongClick = { item ->
                                isSelectionMode = true
                                selectedItemIds = selectedItemIds + item.id
                            },
                            onTogglePin = onTogglePin,
                            onToggleFavorite = onToggleFavorite,
                            onDeleteItem = onDeleteItem,
                            onCopyItem = onCopyItemToClipboard,
                            onSendToDevice = { item ->
                                itemToSendToDevice = item
                            }
                        )
                    }
                }

                ImeViewMode.EMOJI_PICKER -> {
                    EmojiPickerPanel(
                        onEmojiSelected = { emoji -> onInsertText(emoji) },
                        onBackspace = onBackspace,
                        onClose = { viewMode = ImeViewMode.QWERTY }
                    )
                }

                ImeViewMode.EDITING_TOOLS -> {
                    EditingToolsPanel(
                        onLeft = { onMoveCursorLeft?.invoke() ?: onInsertText("") },
                        onRight = { onMoveCursorRight?.invoke() ?: onInsertText("") },
                        onSelectAll = { onSelectAll?.invoke() },
                        onCut = { onCut?.invoke() },
                        onCopy = { onCopy?.invoke() },
                        onPaste = { onPaste?.invoke() },
                        onClose = { viewMode = ImeViewMode.QWERTY }
                    )
                }

                ImeViewMode.DEVICES_PANEL -> {
                    ImeDevicesPanel(
                        devices = devices,
                        onSendCurrentItem = { devId ->
                            if (clipboardItems.isNotEmpty()) {
                                onSendItemToDevice?.invoke(clipboardItems.first(), devId)
                            }
                        },
                        onClose = { viewMode = ImeViewMode.QWERTY }
                    )
                }

                ImeViewMode.DIAGNOSTICS_PANEL -> {
                    val connectedCount = devices.count { it.connectionState == com.example.data.model.ConnectionState.CONNECTED }
                    ImeDiagnosticsPanel(
                        syncStatus = syncStatus,
                        historyCount = clipboardItems.size,
                        devicesCount = devices.count { !it.isLocalDevice },
                        connectedCount = connectedCount,
                        onClose = { viewMode = ImeViewMode.QWERTY }
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// 1. UNIVERSAL TOOLBAR
// -------------------------------------------------------------------------------------------------
@Composable
fun UniversalImeToolbar(
    currentMode: ImeViewMode,
    syncStatus: ImeSyncStatus,
    historyCount: Int,
    isSelectionMode: Boolean,
    selectedCount: Int,
    onModeChange: (ImeViewMode) -> Unit,
    onDeleteSelected: () -> Unit,
    onCancelSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onToggleSyncMode: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .testTag("ime_toolbar"),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        if (isSelectionMode) {
            // Selection Mode Header
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(
                        onClick = onCancelSelection,
                        modifier = Modifier.size(32.dp).testTag("cancel_selection_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel Selection",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        text = "$selectedCount selected",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = onSelectAll,
                        modifier = Modifier.size(32.dp).testTag("select_all_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SelectAll,
                            contentDescription = "Select All",
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = onDeleteSelected,
                        enabled = selectedCount > 0,
                        modifier = Modifier.size(32.dp).testTag("delete_selected_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Selected",
                            tint = if (selectedCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        } else {
            // Normal Toolbar with Sync Badge & Feature Navigation
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Sync Status Pill (Clickable to cycle/toggle mode)
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onToggleSyncMode?.invoke() }
                        .testTag("ime_sync_status_badge"),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(syncStatus.dotColor)
                        )
                        Text(
                            text = syncStatus.label,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Toolbar Buttons
                ToolbarTabButton(
                    icon = Icons.AutoMirrored.Outlined.Assignment,
                    label = "Clipboard",
                    badgeCount = historyCount,
                    isSelected = currentMode == ImeViewMode.CLIPBOARD_HISTORY,
                    testTag = "toggle_clipboard_btn",
                    onClick = { onModeChange(ImeViewMode.CLIPBOARD_HISTORY) }
                )

                ToolbarTabButton(
                    icon = Icons.Outlined.Devices,
                    label = "Devices",
                    isSelected = currentMode == ImeViewMode.DEVICES_PANEL,
                    testTag = "ime_devices_btn",
                    onClick = { onModeChange(ImeViewMode.DEVICES_PANEL) }
                )

                ToolbarTabButton(
                    icon = Icons.Outlined.Mood,
                    label = "Emoji",
                    isSelected = currentMode == ImeViewMode.EMOJI_PICKER,
                    testTag = "ime_emoji_btn",
                    onClick = { onModeChange(ImeViewMode.EMOJI_PICKER) }
                )

                ToolbarTabButton(
                    icon = Icons.Default.Edit,
                    label = "Edit",
                    isSelected = currentMode == ImeViewMode.EDITING_TOOLS,
                    testTag = "ime_edit_tools_btn",
                    onClick = { onModeChange(ImeViewMode.EDITING_TOOLS) }
                )

                ToolbarTabButton(
                    icon = Icons.Default.Analytics,
                    label = "Diagnostics",
                    isSelected = currentMode == ImeViewMode.DIAGNOSTICS_PANEL,
                    testTag = "ime_diagnostics_btn",
                    onClick = { onModeChange(ImeViewMode.DIAGNOSTICS_PANEL) }
                )

                if (currentMode != ImeViewMode.QWERTY) {
                    IconButton(
                        onClick = { onModeChange(ImeViewMode.QWERTY) },
                        modifier = Modifier.size(36.dp).testTag("ime_keyboard_return_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Keyboard,
                            contentDescription = "Return to Keyboard",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ToolbarTabButton(
    icon: ImageVector,
    label: String,
    badgeCount: Int? = null,
    isSelected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .testTag(testTag),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (badgeCount != null && badgeCount > 0) {
                Surface(
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape
                ) {
                    Text(
                        text = "$badgeCount",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// 2. QWERTY KEYBOARD LAYOUT
// -------------------------------------------------------------------------------------------------
@Composable
fun QwertyKeyboardLayout(
    isShiftActive: Boolean,
    isCapsLockActive: Boolean,
    onToggleShift: () -> Unit,
    onKeyClick: (String) -> Unit,
    onSwitchToSymbols: () -> Unit,
    onSpace: () -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit
) {
    val row1 = listOf("q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5", "y" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0")
    val row2 = listOf("a" to "@", "s" to "#", "d" to "$", "f" to "%", "g" to "&", "h" to "*", "j" to "-", "k" to "+", "l" to "=")
    val row3 = listOf("z" to "(", "x" to ")", "c" to "\"", "v" to "'", "b" to ":", "n" to ";", "m" to "/")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("qwerty_layout"),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        // Row 1
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            row1.forEach { (char, hint) ->
                KeyWithHintButton(
                    char = if (isShiftActive || isCapsLockActive) char.uppercase() else char,
                    hint = hint,
                    onClick = { onKeyClick(char) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Row 2
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            row2.forEach { (char, hint) ->
                KeyWithHintButton(
                    char = if (isShiftActive || isCapsLockActive) char.uppercase() else char,
                    hint = hint,
                    onClick = { onKeyClick(char) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Row 3
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Shift Key
            SpecialKeyButton(
                text = if (isCapsLockActive) "⇪" else "⇧",
                isHighlighted = isShiftActive || isCapsLockActive,
                onClick = onToggleShift,
                modifier = Modifier
                    .weight(1.5f)
                    .testTag("key_shift")
            )

            row3.forEach { (char, hint) ->
                KeyWithHintButton(
                    char = if (isShiftActive || isCapsLockActive) char.uppercase() else char,
                    hint = hint,
                    onClick = { onKeyClick(char) },
                    modifier = Modifier.weight(1f)
                )
            }

            // Backspace Key with continuous repeating acceleration gesture
            SpecialKeyButton(
                icon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Backspace,
                        contentDescription = "Backspace",
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = onBackspace,
                isRepeating = true,
                modifier = Modifier
                    .weight(1.5f)
                    .testTag("key_backspace")
            )
        }

        // Row 4 (Bottom controls)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SpecialKeyButton(
                text = "?123",
                onClick = onSwitchToSymbols,
                modifier = Modifier.weight(1.4f).testTag("key_switch_symbols")
            )

            SpecialKeyButton(
                text = ",",
                onClick = { onKeyClick(",") },
                modifier = Modifier.weight(1f)
            )

            // Space Bar
            Box(
                modifier = Modifier
                    .weight(4.2f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { onSpace() }
                    .testTag("key_space"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Universal Keyboard",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                )
            }

            SpecialKeyButton(
                text = ".",
                onClick = { onKeyClick(".") },
                modifier = Modifier.weight(1f)
            )

            // Enter Key
            SpecialKeyButton(
                icon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardReturn,
                        contentDescription = "Enter",
                        modifier = Modifier.size(18.dp)
                    )
                },
                isHighlighted = true,
                onClick = onEnter,
                modifier = Modifier
                    .weight(1.4f)
                    .testTag("key_enter")
            )
        }
    }
}

// -------------------------------------------------------------------------------------------------
// 3. SYMBOLS & NUMBERS KEYBOARD LAYOUT
// -------------------------------------------------------------------------------------------------
@Composable
fun SymbolsKeyboardLayout(
    onKeyClick: (String) -> Unit,
    onSwitchToQwerty: () -> Unit,
    onSpace: () -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit
) {
    val row1 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
    val row2 = listOf("@", "#", "$", "%", "&", "*", "-", "+", "(", ")")
    val row3 = listOf("=", "<", ">", "/", "\\", "~", "^", "_", "[", "]")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("symbols_layout"),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            row1.forEach { char ->
                KeyButton(text = char, onClick = { onKeyClick(char) }, modifier = Modifier.weight(1f))
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            row2.forEach { char ->
                KeyButton(text = char, onClick = { onKeyClick(char) }, modifier = Modifier.weight(1f))
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            SpecialKeyButton(
                text = "{ }",
                onClick = { onKeyClick("{}") },
                modifier = Modifier.weight(1.2f)
            )

            row3.forEach { char ->
                KeyButton(text = char, onClick = { onKeyClick(char) }, modifier = Modifier.weight(1f))
            }

            SpecialKeyButton(
                icon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Backspace,
                        contentDescription = "Backspace",
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = onBackspace,
                isRepeating = true,
                modifier = Modifier.weight(1.5f).testTag("key_backspace")
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            SpecialKeyButton(
                text = "ABC",
                isHighlighted = true,
                onClick = onSwitchToQwerty,
                modifier = Modifier.weight(1.5f).testTag("key_switch_abc")
            )

            SpecialKeyButton(text = "!", onClick = { onKeyClick("!") }, modifier = Modifier.weight(1f))

            Box(
                modifier = Modifier
                    .weight(3.8f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { onSpace() }
                    .testTag("key_space"),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "space", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }

            SpecialKeyButton(text = "?", onClick = { onKeyClick("?") }, modifier = Modifier.weight(1f))

            SpecialKeyButton(
                icon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardReturn,
                        contentDescription = "Enter",
                        modifier = Modifier.size(18.dp)
                    )
                },
                isHighlighted = true,
                onClick = onEnter,
                modifier = Modifier.weight(1.5f).testTag("key_enter")
            )
        }
    }
}

// -------------------------------------------------------------------------------------------------
// 4. RICH CLIPBOARD HISTORY PANEL
// -------------------------------------------------------------------------------------------------
@Composable
fun ClipboardHistoryPanel(
    clipboardItems: List<ClipboardItem>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedCategoryIndex: Int,
    onCategoryIndexChange: (Int) -> Unit,
    isSelectionMode: Boolean,
    selectedItemIds: Set<String>,
    revealedSensitiveItemIds: Set<String>,
    onToggleRevealSensitive: (String) -> Unit,
    onItemClick: (ClipboardItem) -> Unit,
    onItemLongClick: (ClipboardItem) -> Unit,
    onTogglePin: ((String) -> Unit)?,
    onToggleFavorite: ((String) -> Unit)?,
    onDeleteItem: ((String) -> Unit)?,
    onCopyItem: ((ClipboardItem) -> Unit)?,
    onSendToDevice: ((ClipboardItem) -> Unit)?
) {
    val categories = listOf("All", "Pinned", "Favorites", "Text", "URLs", "Code", "Images", "Files", "Synced", "Local")

    val filteredItems = remember(clipboardItems, searchQuery, selectedCategoryIndex) {
        clipboardItems.filter { item ->
            val matchesSearch = searchQuery.isBlank() ||
                    item.content.contains(searchQuery, ignoreCase = true) ||
                    item.sourceDeviceName.contains(searchQuery, ignoreCase = true) ||
                    (item.fileName?.contains(searchQuery, ignoreCase = true) == true) ||
                    item.type.contains(searchQuery, ignoreCase = true)

            val matchesCategory = when (selectedCategoryIndex) {
                1 -> item.isPinned
                2 -> item.isFavorite
                3 -> item.type == ClipboardItem.TYPE_TEXT && !SmartContentClassifier.isSensitive(item.content)
                4 -> item.type == ClipboardItem.TYPE_URL || SmartContentClassifier.classify(item) == SmartContentClassifier.ContentCategory.URL
                5 -> item.type == ClipboardItem.TYPE_CODE || SmartContentClassifier.classify(item) == SmartContentClassifier.ContentCategory.CODE
                6 -> item.type == ClipboardItem.TYPE_IMAGE
                7 -> item.type == ClipboardItem.TYPE_FILE
                8 -> !item.sourceDeviceId.startsWith("dev_local") && item.sourceDeviceId.isNotBlank()
                9 -> item.sourceDeviceId.startsWith("dev_local") || item.sourceDeviceId.isBlank()
                else -> true
            }

            matchesSearch && matchesCategory
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(230.dp)
            .testTag("clipboard_panel"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Search bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .testTag("ime_clipboard_search"),
                placeholder = { Text("Search clipboard history...", fontSize = 12.sp) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear",
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        }

        // Category Filter Chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(categories.size) { index ->
                FilterChip(
                    selected = selectedCategoryIndex == index,
                    onClick = { onCategoryIndexChange(index) },
                    label = { Text(categories[index], fontSize = 11.sp) },
                    modifier = Modifier.height(30.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }

        // History Items List
        if (filteredItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Assignment,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = if (clipboardItems.isEmpty()) "Clipboard history is empty" else "No matching items found",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(
                    items = filteredItems,
                    key = { it.id }
                ) { item ->
                    val isSelected = selectedItemIds.contains(item.id)
                    val isSensitive = SmartContentClassifier.isSensitive(item.content)
                    val isRevealed = revealedSensitiveItemIds.contains(item.id)

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onItemClick(item) }
                            .testTag("clip_item_${item.id}"),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                // Content Preview
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (isSelectionMode) {
                                        Icon(
                                            imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                            contentDescription = null,
                                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    if (isSensitive) {
                                        Icon(
                                            imageVector = Icons.Default.Lock,
                                            contentDescription = "Sensitive content",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }

                                    Text(
                                        text = if (isSensitive && !isRevealed) SmartContentClassifier.maskSensitiveText(item.content) else item.content,
                                        style = if (item.type == ClipboardItem.TYPE_CODE) {
                                            MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                                        } else {
                                            MaterialTheme.typography.bodyMedium
                                        },
                                        fontSize = 13.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                // Quick Action Buttons
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    if (isSensitive) {
                                        IconButton(
                                            onClick = { onToggleRevealSensitive(item.id) },
                                            modifier = Modifier.size(26.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (isRevealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                contentDescription = "Toggle sensitive reveal",
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }

                                    if (onTogglePin != null) {
                                        IconButton(
                                            onClick = { onTogglePin(item.id) },
                                            modifier = Modifier.size(26.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (item.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                                contentDescription = "Pin",
                                                tint = if (item.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }

                                    if (onToggleFavorite != null) {
                                        IconButton(
                                            onClick = { onToggleFavorite(item.id) },
                                            modifier = Modifier.size(26.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (item.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                                contentDescription = "Favorite",
                                                tint = if (item.isFavorite) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // Metadata subtitle
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = item.sourceDeviceName,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "•",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    Text(
                                        text = formatTime(item.createdAt),
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    Text(
                                        text = "•",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    Text(
                                        text = item.displaySize,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }

                                if (onSendToDevice != null) {
                                    Text(
                                        text = "Send ➔",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.clickable { onSendToDevice(item) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// 5. EMOJI PICKER PANEL
// -------------------------------------------------------------------------------------------------
@Composable
fun EmojiPickerPanel(
    onEmojiSelected: (String) -> Unit,
    onBackspace: () -> Unit,
    onClose: () -> Unit
) {
    val smileys = listOf("😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇", "🙂", "🙃", "😉", "😌", "😍", "🥰", "😘", "😗", "😙", "😚", "😋", "😛", "😜", "🤪", "😝", "🤑", "🤗", "🤭", "🤫", "🤔", "🤐", "🤨", "😐", "😑", "😶", "😏", "😒", "🙄", "😬", "🤥")
    val gestures = listOf("👍", "👎", "👌", "✌️", "🤞", "🤟", "🤘", "🤙", "👈", "👉", "👆", "👇", "☝️", "✋", "🤚", "🖐️", "🖖", "👋", "👏", "🙌", "👐", "🤲", "🤝", "🙏", "💪", "🦾", "🖕", "✍️", "🤳", "💅")
    val symbols = listOf("❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔", "❣️", "💕", "💞", "💓", "💗", "💖", "💘", "💝", "💟", "☮️", "✝️", "☪️", "🕉️", "☸️", "✡️", "🔯", "🕎", "☯️", "☦️", "⭐", "🌟", "✨", "⚡", "💥", "🔥")
    val tech = listOf("💻", "🖥️", "📱", "📲", "🖨️", "⌨️", "🖱️", "💾", "💿", "📀", "📷", "📸", "📹", "🎥", "📽️", "📻", "🎙️", "🎧", "📡", "🔋", "🔌", "💡", "🔦", "🕯️", "🧯", "🛢️", "💸", "💵", "💴", "💶")

    var selectedEmojiCategory by remember { mutableIntStateOf(0) }
    val currentEmojis = when (selectedEmojiCategory) {
        0 -> smileys
        1 -> gestures
        2 -> symbols
        else -> tech
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(230.dp)
            .testTag("ime_emoji_panel"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Emoji Category Tabs
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Smileys", "Hands", "Hearts", "Tech").forEachIndexed { index, name ->
                    FilterChip(
                        selected = selectedEmojiCategory == index,
                        onClick = { selectedEmojiCategory = index },
                        label = { Text(name, fontSize = 11.sp) },
                        modifier = Modifier.height(28.dp)
                    )
                }
            }

            IconButton(onClick = onBackspace, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Backspace,
                    contentDescription = "Backspace",
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Emoji Grid
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 38.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(currentEmojis) { emoji ->
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onEmojiSelected(emoji) }
                        .testTag("emoji_$emoji"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = emoji, fontSize = 20.sp)
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// 6. TEXT EDITING & CURSOR CONTROL PANEL
// -------------------------------------------------------------------------------------------------
@Composable
fun EditingToolsPanel(
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onSelectAll: () -> Unit,
    onCut: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(230.dp)
            .padding(8.dp)
            .testTag("ime_editing_panel"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Cursor Navigation & Text Editing",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SpecialKeyButton(
                icon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Left", modifier = Modifier.size(18.dp)) },
                onClick = onLeft,
                modifier = Modifier.weight(1f).testTag("edit_cursor_left")
            )
            SpecialKeyButton(
                icon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Right", modifier = Modifier.size(18.dp)) },
                onClick = onRight,
                modifier = Modifier.weight(1f).testTag("edit_cursor_right")
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SpecialKeyButton(
                text = "Select All",
                onClick = onSelectAll,
                modifier = Modifier.weight(1f).testTag("edit_select_all")
            )
            SpecialKeyButton(
                text = "Cut",
                onClick = onCut,
                modifier = Modifier.weight(1f).testTag("edit_cut")
            )
            SpecialKeyButton(
                text = "Copy",
                onClick = onCopy,
                modifier = Modifier.weight(1f).testTag("edit_copy")
            )
            SpecialKeyButton(
                text = "Paste",
                onClick = onPaste,
                modifier = Modifier.weight(1f).testTag("edit_paste")
            )
        }
    }
}

// -------------------------------------------------------------------------------------------------
// 7. DEVICES MANAGEMENT PANEL IN IME
// -------------------------------------------------------------------------------------------------
@Composable
fun ImeDevicesPanel(
    devices: List<Device>,
    onSendCurrentItem: (String) -> Unit,
    onClose: () -> Unit
) {
    val remoteDevices = devices.filter { !it.isLocalDevice }
    val localDevice = devices.firstOrNull { it.isLocalDevice }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(230.dp)
            .padding(6.dp)
            .testTag("ime_devices_panel"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Devices (${remoteDevices.size} Peers)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Zero-Cloud Local P2P",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (remoteDevices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Devices,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = "Searching local network for peers...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(remoteDevices) { device ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = device.deviceName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(
                                                when (device.connectionState) {
                                                    com.example.data.model.ConnectionState.CONNECTED -> Color(0xFF4CAF50)
                                                    com.example.data.model.ConnectionState.CONNECTING -> Color(0xFFFFB300)
                                                    else -> if (device.isOnline) Color(0xFF2196F3) else Color(0xFF9E9E9E)
                                                }
                                            )
                                    )
                                    Text(
                                        text = when (device.connectionState) {
                                            com.example.data.model.ConnectionState.CONNECTED -> "Connected • ${device.platform.displayName}"
                                            com.example.data.model.ConnectionState.CONNECTING -> "Connecting... • ${device.platform.displayName}"
                                            else -> if (device.isOnline) "Discovered (${device.ipAddress ?: "LAN"})" else "Offline"
                                        },
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }

                            IconButton(
                                onClick = { onSendCurrentItem(device.deviceId) },
                                modifier = Modifier.size(32.dp).testTag("send_to_device_${device.deviceId}")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send to device",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// 8. DIAGNOSTICS & PRIVACY PANEL IN IME
// -------------------------------------------------------------------------------------------------
@Composable
fun ImeDiagnosticsPanel(
    syncStatus: ImeSyncStatus,
    historyCount: Int,
    devicesCount: Int,
    connectedCount: Int = 0,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(230.dp)
            .verticalScroll(rememberScrollState())
            .padding(8.dp)
            .testTag("ime_diagnostics_panel"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "Transport Diagnostics",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                DiagnosticRow(label = "Sync State", value = syncStatus.label)
                DiagnosticRow(label = "Active Transport", value = "Wi-Fi LAN (TCP Socket)")
                DiagnosticRow(label = "History Items", value = "$historyCount stored locally")
                DiagnosticRow(label = "Discovered Peers", value = "$devicesCount on local network")
                DiagnosticRow(label = "Active Sessions", value = "$connectedCount connected")
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
        ) {
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Privacy First: Zero keystroke logging. No cloud servers. Pure local P2P transport.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
}

// -------------------------------------------------------------------------------------------------
// KEY BUTTON COMPONENTS
// -------------------------------------------------------------------------------------------------
@Composable
fun KeyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onClick() }
            .testTag("key_$text"),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun KeyWithHintButton(
    char: String,
    hint: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onClick() }
            .testTag("key_$char"),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = hint,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 3.dp, top = 1.dp)
        )
        Text(
            text = char,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

fun Modifier.repeatingClickable(
    initialDelayMillis: Long = 350L,
    minDelayMillis: Long = 40L,
    onClick: () -> Unit
): Modifier = this.pointerInput(Unit) {
    coroutineScope {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            onClick()
            val job = launch {
                delay(initialDelayMillis)
                var currentDelay = 120L
                while (true) {
                    onClick()
                    delay(currentDelay)
                    if (currentDelay > minDelayMillis) {
                        currentDelay = (currentDelay * 0.85f).toLong().coerceAtLeast(minDelayMillis)
                    }
                }
            }
            do {
                val event = awaitPointerEvent()
            } while (event.changes.any { it.pressed })
            job.cancel()
        }
    }
}

@Composable
fun SpecialKeyButton(
    text: String? = null,
    icon: (@Composable () -> Unit)? = null,
    isHighlighted: Boolean = false,
    isRepeating: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clickModifier = if (isRepeating) {
        Modifier.repeatingClickable(onClick = onClick)
    } else {
        Modifier.clickable { onClick() }
    }

    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isHighlighted) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .then(clickModifier),
        contentAlignment = Alignment.Center
    ) {
        if (text != null) {
            Text(
                text = text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isHighlighted) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else icon?.invoke()
    }
}

private fun formatTime(millis: Long): String {
    val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    return formatter.format(Date(millis))
}

// -------------------------------------------------------------------------------------------------
// 12. TARGET DEVICE PICKER PANEL
// -------------------------------------------------------------------------------------------------
@Composable
fun TargetDevicePickerPanel(
    item: ClipboardItem,
    devices: List<Device>,
    onSelectTargetDevice: (deviceId: String) -> Unit,
    onCancel: () -> Unit
) {
    val remoteDevices = devices.filter { !it.isLocalDevice }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(230.dp)
            .testTag("target_device_picker_panel"),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Send To Device",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = item.content.take(35) + if (item.content.length > 35) "..." else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("close_device_picker_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            HorizontalDivider()

            if (remoteDevices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Devices,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "No remote devices discovered nearby",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    item(key = "send_all_target") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectTargetDevice("") }
                                .testTag("target_device_all"),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Sync,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "All Devices (Broadcast)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    items(remoteDevices, key = { "target_${it.deviceId}" }) { dev ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectTargetDevice(dev.deviceId) }
                                .testTag("target_device_${dev.deviceId}"),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = dev.deviceName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = if (dev.connectionState == com.example.data.model.ConnectionState.CONNECTED) "Connected" else if (dev.isOnline) "Online (${dev.ipAddress ?: "LAN"})" else "Offline",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (dev.connectionState == com.example.data.model.ConnectionState.CONNECTED) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline
                                    )
                                }
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
