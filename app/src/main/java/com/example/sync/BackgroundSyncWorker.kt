package com.example.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.core.identity.DeviceTrustManager
import com.example.core.policy.SyncPolicyManager
import com.example.data.database.entity.DeliveryState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background WorkManager Worker responsible for:
 * - Flushing persistent pending delivery queue upon network restoration / constraints satisfied.
 * - Restoring connection to trusted peers after process recreation.
 * - Enforcing user synchronization policies (AUTO_SYNC, PAUSED, LOCAL_ONLY).
 * - Operating strictly as a client of the authoritative [SyncRuntime] without creating duplicate sessions or sockets.
 */
class BackgroundSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "BackgroundSyncWorker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "BackgroundSyncWorker executing...")

            // 1. Ensure the single authoritative runtime is initialized
            val runtime = SyncRuntime.initialize(applicationContext)

            // 2. Evaluate current user policy
            val policy = SyncPolicyManager.getPolicy()
            if (policy.isSyncPaused) {
                Log.d(TAG, "Sync is paused by user policy. Skipping background delivery.")
                return@withContext Result.success()
            }
            if (!policy.isAutoSyncEnabled) {
                Log.d(TAG, "Sync is set to Local Only. Skipping background delivery.")
                return@withContext Result.success()
            }

            // 3. Check network state
            val isNetworkAvailable = runtime.networkPresenceMonitor.isWifiAvailable.value
            if (!isNetworkAvailable) {
                Log.d(TAG, "Network unavailable during background worker run. Retrying when network restored.")
                return@withContext Result.retry()
            }

            // 4. Check for trusted peers and ensure transport server/discovery is alive if needed
            val trustedPeers = DeviceTrustManager.getAllTrustedPeers()
            if (trustedPeers.isEmpty()) {
                Log.d(TAG, "No trusted peers configured. Background work complete.")
                return@withContext Result.success()
            }

            // 5. Reconnect trusted peers and process persistent pending deliveries via authoritative SyncEngine
            runtime.localWifiTransport.reconnectAllTrustedPeers()

            val pendingDeliveries = runtime.repository.getAllDeliveries().filter { it.state == DeliveryState.PENDING.name }
            Log.d(TAG, "Found ${pendingDeliveries.size} pending deliveries to process.")

            if (pendingDeliveries.isNotEmpty()) {
                runtime.syncEngine.processPendingDeliveries()
            }

            // Check if any deliveries are still pending retry
            val remainingPending = runtime.repository.getAllDeliveries().filter { it.state == DeliveryState.PENDING.name }
            if (remainingPending.any { it.attemptCount < 5 }) {
                Log.d(TAG, "Some pending deliveries still remaining. Requesting retry.")
                Result.retry()
            } else {
                Log.d(TAG, "Background sync completed successfully.")
                Result.success()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error executing BackgroundSyncWorker", e)
            Result.retry()
        }
    }
}
