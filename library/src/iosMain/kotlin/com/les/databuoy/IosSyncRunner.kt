package com.les.databuoy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import platform.Foundation.NSLog

/**
 * iOS equivalent of Android's `SyncWorker`.
 *
 * Runs the [SyncUpCoordinator] against all registered services and reports
 * success/failure via a completion callback. Used by [IosSyncScheduleNotifier]
 * for BGTask-triggered syncs and by [DataBuoy.syncNow] for on-demand syncs.
 */
class IosSyncRunner {

    /**
     * Performs a full sync-up pass across all registered services.
     *
     * @param completion called with `true` when the pending queue is fully
     *   drained (or only blocked by conflicts), `false` otherwise.
     */
    fun performSync(completion: (Boolean) -> Unit) {
        val services = DataBuoy.registeredServices
        if (services.isEmpty()) {
            NSLog("$TAG: No services registered — skipping sync")
            completion(true)
            return
        }

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val database = createSyncDatabase()
                val coordinator = SyncUpCoordinator(
                    participants = services.toList(),
                    database = database,
                )
                val totalSynced = coordinator.syncUpAll()
                val status = DataBuoyStatus(database)
                val remainingPendingCount = status.pendingRequestCount.value
                val hasPendingConflicts = status.hasPendingConflicts.value

                SyncLog.d(
                    TAG,
                    "Sync finished: synced $totalSynced items, " +
                        "remainingPending=$remainingPendingCount, hasConflicts=$hasPendingConflicts"
                )

                val success = remainingPendingCount == 0 || hasPendingConflicts
                completion(success)
            } catch (e: Exception) {
                SyncLog.e(TAG, "Sync failed", e)
                completion(false)
            }
        }
    }

    companion object {
        private const val TAG = "IosSyncRunner"
    }
}
