package com.les.databuoy.sync

import com.les.databuoy.globalconfigs.DataBuoy
import com.les.databuoy.globalconfigs.DataBuoyStatus
import com.les.databuoy.globalconfigs.DatabaseOverride
import com.les.databuoy.globalconfigs.createSyncDatabase
import com.les.databuoy.utils.SyncLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Common sync-up runner used by [DataBuoy.syncNow] and platform-specific
 * background workers ([com.les.databuoy.SyncWorker] on Android,
 * `IosSyncScheduleNotifier` on iOS).
 *
 * Extracts [com.les.databuoy.sync.SyncDriver] instances from the registered
 * services and delegates to [SyncUpCoordinator] for ordered upload.
 */
internal object SyncRunner {

    private const val TAG = "SyncRunner"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Runs a full sync-up pass across all registered services.
     *
     * @return `true` when the pending queue is fully drained or only blocked
     *   by unresolved conflicts, `false` if pending requests remain.
     */
    suspend fun performSyncUp(): Boolean {
        val services = DataBuoy.registeredServices.toList()
        if (services.isEmpty()) {
            SyncLog.d(TAG, "No services registered — skipping sync")
            return true
        }

        val drivers = services.map { it.syncDriver }
        val database = DatabaseOverride.database ?: createSyncDatabase()
        val coordinator = SyncUpCoordinator(
            drivers = drivers,
            database = database,
        )
        val totalSynced = coordinator.syncUpAll()
        DataBuoyStatus.shared.refresh()
        val remainingPendingCount = DataBuoyStatus.shared.pendingRequestCount.value
        val hasPendingConflicts = DataBuoyStatus.shared.hasPendingConflicts.value

        SyncLog.d(
            TAG,
            "Sync finished: synced $totalSynced items, " +
                "remainingPending=$remainingPendingCount, hasConflicts=$hasPendingConflicts"
        )

        return remainingPendingCount == 0 || hasPendingConflicts
    }

    /**
     * Launches [performSyncUp] on a background coroutine and reports the
     * result via [completion].
     */
    fun launchSyncUp(completion: (Boolean) -> Unit = {}) {
        scope.launch {
            try {
                completion(performSyncUp())
            } catch (e: Exception) {
                SyncLog.e(TAG, "Sync failed", e)
                completion(false)
            }
        }
    }
}
