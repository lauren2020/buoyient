package com.les.databuoy.sync

import com.les.databuoy.globalconfigs.DataBuoyStatus
import com.les.databuoy.utils.SyncLog
import com.les.databuoy.db.SyncDatabase

/**
 * Coordinates sync-up across multiple services, dispatching pending
 * requests in global insertion order rather than per-service order.
 *
 * Each [SyncDriver] handles sync-up for its own service. The coordinator
 * queries the global pending-request queue and dispatches each entry to
 * the correct driver by [SyncDriver.serviceName].
 */
public class SyncUpCoordinator(
    private val drivers: List<SyncDriver<*, *>>,
    private val database: SyncDatabase,
    private val status: DataBuoyStatus = DataBuoyStatus(database),
) {
    /**
     * Uploads all pending requests across all services in the order they
     * were originally queued (by auto-increment `pending_request_id`).
     *
     * @return the total number of requests that were successfully synced.
     */
    public suspend fun syncUpAll(): Int {
        try {
            SyncLog.d(TAG, "Starting global sync up across ${drivers.size} services...")

            // Block all uploads globally if any item (in any service) has unresolved
            // conflicts. Cross-item request ordering may create dependencies, so it is
            // unsafe to upload anything until every conflict is resolved.
            if (database.syncPendingEventsQueries.hasAnyConflicts().executeAsOne()) {
                SyncLog.w(TAG, "Skipping sync-up: unresolved conflicts exist. Resolve all conflicts before uploads can resume.")
                return 0
            }

            // Query all pending requests across all services, ordered by insertion order.
            val globalQueue = database.syncPendingEventsQueries
                .getAllPendingRequestsGlobally()
                .executeAsList()

            SyncLog.d(TAG, "Found ${globalQueue.size} pending sync rows globally.")

            // Build serviceName → driver map for dispatch.
            val driverMap = drivers.associateBy { it.serviceName }

            var syncedCount = 0
            for (entry in globalQueue) {
                val driver = driverMap[entry.service_name]
                if (driver == null) {
                    SyncLog.w(TAG, "No service registered for '${entry.service_name}', skipping pending_request_id=${entry.pending_request_id}")
                    continue
                }
                try {
                    if (driver.syncUpSinglePendingRequest(entry.pending_request_id.toInt())) {
                        syncedCount++
                    }
                } catch (e: SyncUpRetryLaterException) {
                    SyncLog.w(
                        TAG,
                        "Sync-up retry requested — stopping this pass so the caller can retry later. " +
                            "($syncedCount synced so far)"
                    )
                    return syncedCount
                }
            }

            SyncLog.d(TAG, "Global sync complete: $syncedCount/${globalQueue.size} succeeded")
            return syncedCount
        } finally {
            status.refresh()
        }
    }

    private companion object {
        private const val TAG: String = "SyncUpCoordinator"
    }
}