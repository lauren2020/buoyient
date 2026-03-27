package com.elvdev.buoyient.sync

import com.elvdev.buoyient.globalconfigs.Buoyient
import com.elvdev.buoyient.globalconfigs.BuoyientStatus
import com.elvdev.buoyient.globalconfigs.DatabaseOverride
import com.elvdev.buoyient.globalconfigs.createSyncDatabase
import com.elvdev.buoyient.utils.BuoyientLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Common sync-up runner used by [Buoyient.syncNow] and platform-specific
 * background workers ([com.elvdev.buoyient.SyncWorker] on Android,
 * `IosSyncScheduleNotifier` on iOS).
 *
 * Extracts [com.elvdev.buoyient.sync.SyncDriver] instances from the registered
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
        val services = Buoyient.registeredServices.toList()
        if (services.isEmpty()) {
            BuoyientLog.d(TAG, "No services registered — skipping sync")
            return true
        }

        val drivers = services.map { it.syncDriver }
        val database = DatabaseOverride.database ?: createSyncDatabase()
        val coordinator = SyncUpCoordinator(
            drivers = drivers,
            database = database,
        )
        val totalSynced = coordinator.syncUpAll()
        val remainingPendingCount = BuoyientStatus.shared.pendingRequestCount.value
        val hasPendingConflicts = BuoyientStatus.shared.hasPendingConflicts.value

        BuoyientLog.d(
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
                BuoyientLog.e(TAG, "Sync failed", e)
                completion(false)
            }
        }
    }
}
