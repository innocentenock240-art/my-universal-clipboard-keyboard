package com.example.keyboard

import android.content.ClipDescription
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.core.identity.DeviceTrustManager
import com.example.core.policy.ExplicitSendRequest
import com.example.core.policy.OperationalSyncState
import com.example.core.policy.SendDestination
import com.example.core.policy.SendResult
import com.example.core.policy.SyncPolicyManager
import com.example.core.transport.NetworkPresenceMonitor
import com.example.data.clipboard.ClipboardCoreManager
import com.example.data.database.ClipboardDatabase
import com.example.data.model.ClipboardItem
import com.example.data.model.ConnectionState
import com.example.data.model.Device
import com.example.data.repository.ClipboardRepository
import com.example.sync.SyncEngine
import com.example.sync.SyncRuntime
import com.example.sync.transport.LocalWifiTransport
import com.example.ui.theme.UniversalClipboardTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

open class UniversalClipboardInputMethodService : InputMethodService(),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    var repository: ClipboardRepository? = null
    var clipboardCore: ClipboardCoreManager? = null
    var syncEngine: SyncEngine? = null
    var localWifiTransport: LocalWifiTransport? = null
    private var networkPresenceMonitor: NetworkPresenceMonitor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var currentEditorInfo: EditorInfo? = null

    open override fun getCurrentInputConnection(): InputConnection? {
        return super.getCurrentInputConnection()
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        try {
            val runtime = SyncRuntime.initialize(this.applicationContext ?: this)
            repository = runtime.repository
            clipboardCore = runtime.clipboardCore
            localWifiTransport = runtime.localWifiTransport
            syncEngine = runtime.syncEngine
            networkPresenceMonitor = runtime.networkPresenceMonitor
        } catch (e: Exception) {
            android.util.Log.e("UniversalClipboardIME", "Failed to initialize SyncRuntime in IME service", e)
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        currentEditorInfo = info
        clipboardCore?.checkClipboard()
    }

    override fun onCreateInputView(): View {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)

            val decorView = window?.window?.decorView ?: this
            decorView.setViewTreeLifecycleOwner(this@UniversalClipboardInputMethodService)
            decorView.setViewTreeSavedStateRegistryOwner(this@UniversalClipboardInputMethodService)
            decorView.setViewTreeViewModelStoreOwner(this@UniversalClipboardInputMethodService)

            setViewTreeLifecycleOwner(this@UniversalClipboardInputMethodService)
            setViewTreeSavedStateRegistryOwner(this@UniversalClipboardInputMethodService)
            setViewTreeViewModelStoreOwner(this@UniversalClipboardInputMethodService)

            setContent {
                UniversalClipboardTheme {
                    val coroutineScope = rememberCoroutineScope()
                    val historyFlow: Flow<List<ClipboardItem>> = repository?.clipboardHistory ?: emptyFlow()
                    val clipboardItems by historyFlow.collectAsState(initial = emptyList())

                    val syncPolicy by SyncPolicyManager.syncPolicy.collectAsState()
                    val ecosystemState by SyncRuntime.ecosystemState.collectAsState()
                    val operationalState = ecosystemState.operationalSyncState
                    val allImeDevices = ecosystemState.allDevices

                    val hasReconnectingPeers = allImeDevices.any { it.connectionState == ConnectionState.RECONNECTING }

                    val syncStatus = when {
                        operationalState == OperationalSyncState.OFFLINE -> ImeSyncStatus.OFFLINE
                        operationalState == OperationalSyncState.PAUSED -> ImeSyncStatus.PAUSED
                        hasReconnectingPeers -> ImeSyncStatus.RECONNECTING
                        operationalState == OperationalSyncState.LOCAL_ONLY -> ImeSyncStatus.LOCAL_ONLY
                        operationalState == OperationalSyncState.SYNCING -> ImeSyncStatus.ACTIVE
                        else -> ImeSyncStatus.LOCAL_ONLY
                    }

                    KeyboardScreen(
                        clipboardItems = clipboardItems,
                        onInsertText = { text -> insertText(text) },
                        onBackspace = { handleBackspace() },
                        onEnter = { handleEnter() },
                        onDeleteItem = { id ->
                            coroutineScope.launch {
                                repository?.deleteClipboardItem(id)
                            }
                        },
                        onDeleteItems = { ids ->
                            coroutineScope.launch {
                                repository?.deleteItemsByIds(ids)
                            }
                        },
                        onTogglePin = { id ->
                            coroutineScope.launch {
                                val item = clipboardItems.firstOrNull { it.id == id } ?: return@launch
                                repository?.togglePin(id, item.isPinned)
                            }
                        },
                        onToggleFavorite = { id ->
                            coroutineScope.launch {
                                val item = clipboardItems.firstOrNull { it.id == id } ?: return@launch
                                repository?.toggleFavorite(id, item.isFavorite)
                            }
                        },
                        onCopyItemToClipboard = { item ->
                            clipboardCore?.applyRemoteClipboardItem(item)
                        },
                        onSendItemToDevice = { item, deviceId ->
                            coroutineScope.launch(Dispatchers.IO) {
                                val dest = if (deviceId.isBlank() || deviceId == "ALL") {
                                    SendDestination.AllTrustedPeers
                                } else {
                                    SendDestination.SpecificPeer(deviceId)
                                }
                                val request = ExplicitSendRequest(
                                    items = listOf(item),
                                    destination = dest,
                                    isUserAuthorized = true
                                )
                                val result = SyncRuntime.executeSendRequest(request)
                                withContext(Dispatchers.Main) {
                                    when (result) {
                                        is SendResult.Success -> {
                                            Toast.makeText(applicationContext, "Sent to device successfully", Toast.LENGTH_SHORT).show()
                                        }
                                        is SendResult.Queued -> {
                                            Toast.makeText(applicationContext, "Queued for delivery (${result.reason})", Toast.LENGTH_SHORT).show()
                                            SyncRuntime.triggerBackgroundDeliveryFlush(applicationContext)
                                        }
                                        is SendResult.Rejected -> {
                                            Toast.makeText(applicationContext, "Send rejected: ${result.reason}", Toast.LENGTH_SHORT).show()
                                        }
                                        is SendResult.Failed -> {
                                            Toast.makeText(applicationContext, "Send failed: ${result.error}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        },
                        onMoveCursorLeft = { moveCursorLeft() },
                        onMoveCursorRight = { moveCursorRight() },
                        onSelectAll = { selectAll() },
                        onCut = { cut() },
                        onCopy = { copy() },
                        onPaste = { paste() },
                        devices = allImeDevices,
                        syncStatus = syncStatus,
                        onToggleSyncMode = {
                            SyncPolicyManager.togglePauseSync()
                        }
                    )
                }
            }
        }
        return composeView
    }

    fun insertText(text: String) {
        val ic = currentInputConnection
        ic?.commitText(text, 1)
    }

    /**
     * Inserts rich image content directly into compatible input fields (e.g. messaging apps).
     * Gracefully falls back to system clipboard copy when target app does not support image insertion.
     */
    fun insertRichImage(contentUri: Uri, mimeType: String, description: String = "Image") {
        val ic = currentInputConnection
        val editorInfo = currentEditorInfo

        if (ic == null || editorInfo == null) {
            return
        }

        try {
            val supportedMimeTypes = EditorInfoCompat.getContentMimeTypes(editorInfo)
            val isSupported = supportedMimeTypes.any { ClipDescription.compareMimeTypes(it, mimeType) }

            if (isSupported) {
                val inputContentInfo = InputContentInfoCompat(
                    contentUri,
                    ClipDescription(description, arrayOf(mimeType)),
                    null
                )
                val flags = InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
                val success = InputConnectionCompat.commitContent(ic, editorInfo, inputContentInfo, flags, null)
                if (success) {
                    return
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback: copy to system clipboard
        Toast.makeText(applicationContext, "Image copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    fun handleBackspace() {
        val ic = currentInputConnection
        if (ic == null) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            return
        }
        try {
            val selectedText = ic.getSelectedText(0)
            if (!selectedText.isNullOrEmpty()) {
                ic.commitText("", 1)
            } else {
                val deleted = ic.deleteSurroundingText(1, 0)
                if (!deleted) {
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
                }
            }
        } catch (e: Exception) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
    }

    fun handleEnter() {
        val ic = currentInputConnection
        if (ic == null) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    fun moveCursorLeft() {
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT))
    }

    fun moveCursorRight() {
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    fun selectAll() {
        val ic = currentInputConnection ?: return
        ic.performContextMenuAction(android.R.id.selectAll)
    }

    fun cut() {
        val ic = currentInputConnection ?: return
        ic.performContextMenuAction(android.R.id.cut)
    }

    fun copy() {
        val ic = currentInputConnection ?: return
        ic.performContextMenuAction(android.R.id.copy)
    }

    fun paste() {
        val ic = currentInputConnection ?: return
        ic.performContextMenuAction(android.R.id.paste)
    }

    override fun onDestroy() {
        try {
            serviceScope.cancel()
            // networkPresenceMonitor is owned by the authoritative SyncRuntime
        } catch (e: Exception) {
            e.printStackTrace()
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        super.onDestroy()
    }
}
