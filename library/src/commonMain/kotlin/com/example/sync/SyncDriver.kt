package com.example.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * This class is responsible for orchestrating syncing data between the local client and the server.
 */
abstract class SyncDriver<O : SyncableObject<O>, T : ServiceRequestTag>(
    private val serverManager: ServerManager,
    private val connectivityChecker: ConnectivityChecker,
    private val codec: SyncCodec<O>,
    private val serverProcessingConfig: ServerProcessingConfig<O>,
    private val localStoreManager: LocalStoreManager<O, T>,
    private val logger: SyncLogger,
) {

    /**
     * Handles 3-way merge conflict detection and resolution during [syncDownFromServer].
     * Override this property in a service subclass to provide a custom [SyncableObjectMergeHandler]
     * with domain-specific merge policies.
     */
    protected open val mergeHandler: SyncableObjectMergeHandler<O> =
        SyncableObjectMergeHandler(codec)

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var syncDownJob: Job? = null

    init { startPeriodicSyncDown() }

    /**
     * Fetches all data from the server and upserts it into the local db, handling any needed
     * merges or conflicts to do so.
     *
     * This uses [SyncableObjectMergeHandler] to determine the policy for merges and conflicts.
     * Override this value to inject custom merge & conflict handling.
     *
     * Default behavior is to do a simple merge of the local & server data if not fields directly
     * conflict and to mark any rows with fields that do directly conflict with a
     * [SyncableObject.SyncStatus] of [SyncableObject.SyncStatus.Conflict].
     *
     * The request structure used to fetch from the server is configured by [ServerProcessingConfig].
     */
    suspend fun syncDownFromServer() {
        if (!connectivityChecker.isOnline()) {
            // If the client is not connected, do not bother trying to sync.
            return
        }
        val response = when (val fetchConfig = serverProcessingConfig.syncFetchConfig) {
            is SyncFetchConfig.GetFetchConfig -> serverManager.sendRequest(
                HttpRequest(
                    method = HttpRequest.HttpMethod.GET,
                    endpointUrl = fetchConfig.endpoint,
                    requestBody = JsonObject(emptyMap()),
                )
            )

            is SyncFetchConfig.PostFetchConfig -> serverManager.sendRequest(
                HttpRequest(
                    method = HttpRequest.HttpMethod.POST,
                    endpointUrl = fetchConfig.endpoint,
                    requestBody = fetchConfig.requestBody,
                )
            )
        }

        when (response) {
            is ServerManager.ServerManagerResponse.ConnectionError -> {
                logger.w(TAG, "Sync down failed due to connection error. Retrying later.")
                return
            }

            is ServerManager.ServerManagerResponse.ServerResponse -> {
                val syncedAtTimestamp = TimestampFormatter.fromEpochSeconds(response.responseEpochTimestamp)
                val items = serverProcessingConfig.syncFetchConfig.transformItemsListFromResponse(
                    response = response.responseBody,
                )

                var upsertedCount = 0
                var mergedCount = 0
                var conflictCount = 0
                var skippedCount = 0

                items.forEach { serverObj ->
                    try {
                        val upsertResult = upsertServerResultIntoLocal(
                            serverObj = serverObj,
                            syncedAtTimestamp = syncedAtTimestamp,
                        )
                        when (upsertResult) {
                            is UpsertResult.CleanUpsert -> upsertedCount++
                            is UpsertResult.MergedUpsert -> mergedCount++
                            is UpsertResult.ConflictFailure -> conflictCount++
                        }
                    } catch (e: Exception) {
                        logger.e(TAG, "Failed to upsert item (${serverObj.serverId}): ", e)
                        skippedCount++
                    }
                }
                logger.d(TAG, "Sync down complete: ${items.size} fetched, $upsertedCount upserted, $mergedCount merged, $conflictCount conflicts, $skippedCount skipped")
            }
        }
    }

    /**
     * Attempts to upload all locally-stored, unsynced objects to the remote
     * API. For each row where [pending_sync_request] is non-null, this method
     * POSTs the stored request body to the endpoint URL embedded in the
     * pending request and dispatches the success handling based on
     * [PendingSyncRequest.type]:
     *
     * - **CREATE / UPDATE**: [server_id], [version], [last_synced_timestamp],
     *   and [data] are set from the API response.
     * - **VOID**: [last_synced_timestamp] and [data] are set from the API
     *   response (server_id and version are left unchanged).
     *
     * In all cases [pending_sync_request] is cleared and [sync_status] is
     * set to SYNCED on success.
     *
     * @return the number of rows that were successfully synced.
     */
    suspend fun syncUpLocalChanges(): Int {
        logger.d(TAG, "Starting sync up of local changes...")
        // 1. Query all rows with a non-null pending_sync_request.
        val pendingSyncEntries = localStoreManager.pendingRequestQueueManager.getPendingRequests()
        logger.d(TAG, "Found ${pendingSyncEntries.size} pending sync rows.")

        // 2. Attempt to sync each row.
        // We collect pending_request_ids upfront but re-fetch each entry from the DB
        // before processing it so that any rebases applied by earlier iterations
        // (e.g., a CREATE that updates server context for subsequent UPDATEs) are
        // reflected in the entry we actually send.
        val pendingRequestIds = pendingSyncEntries.map { it.pendingRequestId }
        var syncedCount = 0
        for (pendingRequestId in pendingRequestIds) {
            // Re-fetch the entry so we always use the latest rebased state.
            val entry = localStoreManager.pendingRequestQueueManager
                .getPendingRequestById(pendingRequestId)
            if (entry == null) {
                // Entry was cleared by a previous iteration (e.g., squash); skip.
                continue
            }
            try {
                syncUpPendingData(entry)
                syncedCount++
            } catch (e: Exception) {
                logger.e(TAG, "Error syncing ${entry.type} for ${entry.data.clientId}.", e)
            }
        }

        logger.d(TAG, "Sync complete: $syncedCount/${pendingRequestIds.size} succeeded")
        return syncedCount
    }

    // Sync Down Region
    private fun upsertServerResultIntoLocal(
        serverObj: O,
        syncedAtTimestamp: String,
    ): UpsertResult {
        val currentLocalData = localStoreManager.getData(
            clientId = serverObj.clientId,
            serverId = serverObj.serverId,
        )
        return if (currentLocalData == null) {
            // If no existing local entry exists for this data, clean add it to the db.
            localStoreManager.upsertEntry(
                serverObj = serverObj,
                syncedAtTimestamp = syncedAtTimestamp,
                clientId = serverObj.clientId,
            )
            UpsertResult.CleanUpsert
        } else {
            localStoreManager.upsertSyncDownResponseData(
                clientId = currentLocalData.data.clientId,
                lastSyncedTimestamp = syncedAtTimestamp,
                updatedServerData = serverObj,
                mergeHandler = mergeHandler,
            )
        }
    }
    // End region

    // Sync Up Region

    protected suspend fun syncUpPendingData(row: PendingSyncRequest<O>) {
        var request = row.request
        // If the request contains placeholders, updates those based on the latest server context.
        if (row.request.endpointUrl.contains(HttpRequest.SERVER_ID_PLACEHOLDER)) {
            val serverId = row.lastSyncedData?.serverId ?: row.data.serverId
            if (serverId == null) {
                // If the endpoint contains unresolved placeholders and still has no server id,
                // skip this entry.
                // This can happen if the preceding CREATE hasn't synced yet (e.g., it failed).
                // The entry stays in the queue and will be retried on the next sync cycle.
                logger.w(TAG, "Skipping ${row.type} for ${row.data.clientId}: serverId not yet resolved")
                return
            } else {
                request = request.resolveEndpoint(serverId) ?: request
            }
        }
        val requestBodyString = row.request.requestBody.toString()
        if (requestBodyString.contains(HttpRequest.SERVER_ID_PLACEHOLDER)) {
            val serverId = row.lastSyncedData?.serverId ?: row.data.serverId
            if (serverId == null) {
                // If the request body contains unresolved placeholders and still has no server id,
                // skip this entry.
                // This can happen if the preceding CREATE hasn't synced yet (e.g., it failed).
                // The entry stays in the queue and will be retried on the next sync cycle.
                logger.w(TAG, "Skipping ${row.type} for ${row.data.clientId}: serverId not yet resolved")
                return
            } else {
                request = request.resolveBodyServerId(serverId) ?: request
            }
        }
        if (requestBodyString.contains(HttpRequest.VERSION_PLACEHOLDER)) {
            // Resolve the version placeholder in the request body with the most up-to-date version.
            val version = row.lastSyncedData?.version ?: row.data.version
            request = request.resolveBodyVersion(version.toString()) ?: request
        }
        when (val response = serverManager.sendRequest(request)) {
            is ServerManager.ServerManagerResponse.ConnectionError ->
                logger.w(TAG, "Sync up failed due to connection error. Trying again later.")

            is ServerManager.ServerManagerResponse.ServerResponse -> {
                if (
                    !serverProcessingConfig.syncUpConfig.acceptUploadResponseAsProcessed(
                        statusCode = response.statusCode,
                        responseBody = response.responseBody,
                        requestTag = row.requestTag,
                    )
                ) {
                    // If the response status was not accepted as processed, the sync should be retried.
                    // Leave the row in the queue for a future sync attempt but mark it as attempted.
                    // If the row is already marked as attempted, do not try remarking it for performance.
                    if (!row.serverAttemptMade) {
                        localStoreManager.pendingRequestQueueManager.markPendingRequestAsAttempted(row.pendingRequestId)
                    }
                    logger.w(TAG, "Sync failed for pending_request_id: ${row.pendingRequestId} (${row.type}): ${response.statusCode} — it will be retried later.")
                } else {
                    // 3. Parse the response and mark as synced
                    val updatedData = serverProcessingConfig.syncUpConfig.fromResponseBody(
                        requestTag = row.requestTag,
                        responseBody = response.responseBody,
                    )
                    val lastSyncedTimestamp = TimestampFormatter.fromEpochSeconds(response.responseEpochTimestamp)

                    // Clear the queue entry, update the main table, and
                    // propagate server context atomically so concurrent
                    // local writes do not see an inconsistent state.
                    if (updatedData != null) {
                        localStoreManager.upsertPendingRequestSyncResponseData(
                            updatedServerData = updatedData,
                            lastSyncedTimestamp = lastSyncedTimestamp,
                            syncedPendingRequest = row,
                            mergeHandler = mergeHandler,
                        )
                        logger.d(TAG, "Synced ${row.type} for ${row.data.clientId} (server_id=${updatedData.serverId})")
                    } else {
                        localStoreManager.updateLocalDataAfterPendingRequestSync(
                            processedLocalData = row.data,
                            lastSyncedTimestamp = lastSyncedTimestamp,
                            syncedPendingRequest = row,
                        )
                        logger.w(TAG, "Synced ${row.type} for ${row.data.clientId} with pending_request_id: ${row.pendingRequestId}, but failed to resolve latest server data.")
                    }
                }
            }
        }
    }
    // End region

    /**
     * Launches a coroutine that periodically calls [syncDownFromServer] at the interval
     * configured by [SyncFetchConfig.syncCadenceSeconds]. The first sync happens immediately
     * on init. If already running, this is a no-op.
     */
    private fun startPeriodicSyncDown() {
        if (syncDownJob?.isActive == true) return
        val cadenceMs = serverProcessingConfig.syncFetchConfig.syncCadenceSeconds * 1000
        syncDownJob = serviceScope.launch {
            while (isActive) {
                try {
                    syncDownFromServer()
                } catch (e: Exception) {
                    logger.e(TAG, "Periodic sync-down failed: ", e)
                }
                delay(cadenceMs)
            }
        }
    }

    /**
     * Stops the periodic sync-down loop. Can be restarted by calling [startPeriodicSyncDown].
     */
    fun stopPeriodicSyncDown() {
        syncDownJob?.cancel()
        syncDownJob = null
    }

    open fun close() {
        serviceScope.cancel()
    }

    // Sync Down
    sealed class UpsertResult {
        object CleanUpsert : UpsertResult()
        object MergedUpsert : UpsertResult()
        object ConflictFailure : UpsertResult()
    }

    companion object {
        const val TAG = "SyncableObjectService:SyncDriver"
    }
}
