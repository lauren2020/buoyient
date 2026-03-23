package com.les.databuoy

import com.les.databuoy.db.SyncDatabase

/**
 * A participant in cross-service sync-up coordination.
 *
 * Both [SyncDriver] and [SyncableObjectService] implement this interface
 * so the [SyncUpCoordinator] can dispatch pending requests to the correct
 * service without knowing the concrete type parameters.
 */
interface SyncUpParticipant {
    val serviceName: String
    suspend fun syncUpSinglePendingRequest(pendingRequestId: Int): Boolean
}

/**
 * Coordinates sync-up across multiple services, dispatching pending
 * requests in global insertion order rather than per-service order.
 */
class SyncUpCoordinator(
    private val participants: List<SyncUpParticipant>,
    private val database: SyncDatabase,
    private val logger: SyncLogger,
    private val status: DataBuoyStatus = DataBuoyStatus(database),
) {
    /**
     * Uploads all pending requests across all services in the order they
     * were originally queued (by auto-increment `pending_request_id`).
     *
     * @return the total number of requests that were successfully synced.
     */
    suspend fun syncUpAll(): Int {
        try {
            logger.d(TAG, "Starting global sync up across ${participants.size} services...")

            // Block all uploads globally if any item (in any service) has unresolved
            // conflicts. Cross-item request ordering may create dependencies, so it is
            // unsafe to upload anything until every conflict is resolved.
            if (database.syncPendingEventsQueries.hasAnyConflicts().executeAsOne()) {
                logger.w(TAG, "Skipping sync-up: unresolved conflicts exist. Resolve all conflicts before uploads can resume.")
                return 0
            }

            // Query all pending requests across all services, ordered by insertion order.
            val globalQueue = database.syncPendingEventsQueries
                .getAllPendingRequestsGlobally()
                .executeAsList()

            logger.d(TAG, "Found ${globalQueue.size} pending sync rows globally.")

            // Build serviceName → participant map for dispatch.
            val participantMap = participants.associateBy { it.serviceName }

            var syncedCount = 0
            for (entry in globalQueue) {
                val participant = participantMap[entry.service_name]
                if (participant == null) {
                    logger.w(TAG, "No service registered for '${entry.service_name}', skipping pending_request_id=${entry.pending_request_id}")
                    continue
                }
                if (participant.syncUpSinglePendingRequest(entry.pending_request_id.toInt())) {
                    syncedCount++
                }
            }

            logger.d(TAG, "Global sync complete: $syncedCount/${globalQueue.size} succeeded")
            return syncedCount
        } finally {
            status.refresh()
        }
    }

    companion object {
        private const val TAG = "SyncUpCoordinator"
    }
}
