package com.les.databuoy.sync

import com.les.databuoy.syncableobjectservicedatatypes.HttpRequest
import com.les.databuoy.managers.PendingSyncRequest
import com.les.databuoy.serviceconfigs.ServerProcessingConfig
import com.les.databuoy.ServiceRequestTag
import com.les.databuoy.utils.SyncCodec
import com.les.databuoy.serviceconfigs.SyncFetchConfig
import com.les.databuoy.utils.SyncLog
import com.les.databuoy.serviceconfigs.SyncUpResult
import com.les.databuoy.SyncableObject
import com.les.databuoy.utils.TimestampFormatter
import com.les.databuoy.managers.LocalStoreManager
import com.les.databuoy.managers.ServerManager
import com.les.databuoy.serviceconfigs.ConnectivityChecker
import com.les.databuoy.serviceconfigs.SyncableObjectRebaseHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * Thrown by [SyncDriver.syncUpPendingData] when the server returns [SyncUpResult.Failed.Retry],
 * signalling that the sync-up loop should stop immediately and retry after a delay.
 */
internal class SyncUpRetryLaterException(message: String) : Exception(message)

/**
 * Orchestrates syncing data between the local client and the server.
 *
 * Handles sync-down (fetching server data and upserting locally with conflict resolution),
 * sync-up (uploading pending local changes), and periodic sync scheduling.
 *
 * @param autoStart When `true` (the default), periodic sync-down starts immediately on
 *   construction. Pass `false` in tests to prevent background sync from interfering with
 *   test assertions.
 */
public class SyncDriver<O : SyncableObject<O>, T : ServiceRequestTag> internal constructor(
    private val serverManager: ServerManager,
    private val connectivityChecker: ConnectivityChecker,
    private val codec: SyncCodec<O>,
    private val serverProcessingConfig: ServerProcessingConfig<O>,
    private val localStoreManager: LocalStoreManager<O, T>,
    public val serviceName: String,
    public val rebaseHandler: SyncableObjectRebaseHandler<O> = SyncableObjectRebaseHandler(codec),
    autoStart: Boolean = true,
) {

    public val serviceScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Per-clientId Mutex map for application-level mutual exclusion.
    // Serializes all operations (CRUD, sync-down, sync-up) on the same object
    // while allowing full concurrency across different objects.
    //
    // Entries are reference-counted so they are removed when no coroutine is
    // using or waiting on the lock, preventing unbounded map growth.
    private val lockMapMutex = Mutex()
    private val clientLocks = mutableMapOf<String, RefCountedMutex>()

    private class RefCountedMutex {
        val mutex = Mutex()
        var refCount = 0
    }

    // Guards `syncDownJob` against concurrent start/stop calls from different threads.
    // Uses a coroutine Mutex instead of `synchronized(Any())` because the latter has
    // platform-dependent semantics on Kotlin/Native and is not guaranteed to provide
    // mutual exclusion in all Kotlin/Native runtime versions.
    private val syncJobMutex = Mutex()
    @Volatile private var syncDownJob: Job? = null

    init {
        if (autoStart) {
            localStoreManager.scheduleSyncUp()
            startPeriodicSyncDown()
        }
    }

    /**
     * Acquires a per-clientId [Mutex], ensuring that all operations on the same
     * object are serialized. Different objects can still be processed concurrently.
     *
     * This prevents races between user-initiated CRUD, periodic sync-down, and
     * background sync-up when they target the same clientId.
     */
    public suspend fun <R> withClientLock(clientId: String, block: suspend () -> R): R {
        val ref = lockMapMutex.withLock {
            clientLocks.getOrPut(clientId) { RefCountedMutex() }.also { it.refCount++ }
        }
        try {
            return ref.mutex.withLock { block() }
        } finally {
            lockMapMutex.withLock {
                ref.refCount--
                if (ref.refCount == 0) {
                    clientLocks.remove(clientId)
                }
            }
        }
    }

    /**
     * Fetches all data from the server and upserts it into the local db, handling any needed
     * merges or conflicts to do so.
     *
     * This uses [SyncableObjectRebaseHandler] to determine the policy for merges and conflicts.
     * Provide a custom [rebaseHandler] to inject domain-specific merge & conflict handling.
     *
     * Default behavior is to do a simple merge of the local & server data if no fields directly
     * conflict and to mark any rows with fields that do directly conflict with a
     * [SyncableObject.SyncStatus] of [SyncableObject.SyncStatus.Conflict].
     *
     * The request structure used to fetch from the server is configured by [ServerProcessingConfig].
     */
    public suspend fun syncDownFromServer() {
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
                is ServerManager.ServerManagerResponse.ConnectionError,
                is ServerManager.ServerManagerResponse.RequestTimedOut -> {
                    SyncLog.w(TAG, "Sync down failed due to connection error. Retrying later.")
                    return
                }

                is ServerManager.ServerManagerResponse.ServerError -> {
                    SyncLog.w(TAG, "Sync down failed due to server error (${response.statusCode}). Retrying later.")
                    return
                }

                is ServerManager.ServerManagerResponse.Failed -> {
                    SyncLog.w(TAG, "Sync down failed with status ${response.statusCode}. Retrying later.")
                    return
                }

                is ServerManager.ServerManagerResponse.Success -> {
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
                            val upsertResult = withClientLock(serverObj.clientId) {
                                upsertServerResultIntoLocal(
                                    serverObj = serverObj,
                                    syncedAtTimestamp = syncedAtTimestamp,
                                )
                            }
                            when (upsertResult) {
                                is UpsertResult.CleanUpsert -> upsertedCount++
                                is UpsertResult.MergedUpsert -> mergedCount++
                                is UpsertResult.ConflictFailure -> conflictCount++
                            }
                        } catch (e: Exception) {
                            SyncLog.e(TAG, "Failed to upsert item (${serverObj.serverId}): ", e)
                            skippedCount++
                        }
                    }
                    SyncLog.d(TAG, "Sync down complete: ${items.size} fetched, $upsertedCount upserted, $mergedCount merged, $conflictCount conflicts, $skippedCount skipped")
                }
            }
    }

    /**
     * Attempts to sync a single pending request by its ID.
     *
     * Re-fetches the entry from the DB before processing so that any rebases
     * applied by earlier uploads (e.g., a CREATE that updates server context
     * for subsequent UPDATEs) are reflected in the entry we actually send.
     *
     * @return `true` if the request was synced successfully, `false` otherwise.
     */
    public suspend fun syncUpSinglePendingRequest(pendingRequestId: Int): Boolean {
        var entry: PendingSyncRequest<O>? = null
        return try {
            // Re-fetch the entry so we always use the latest rebased state.
            entry = localStoreManager.pendingRequestQueueManager
                .getPendingRequestById(pendingRequestId)
            if (entry == null) {
                // Entry was cleared by a previous iteration (e.g., squash); skip.
                return false
            }
            var synced = false
            withClientLock(entry.data.clientId) {
                // Re-fetch inside the lock to ensure we have the latest state
                // after acquiring exclusive access for this clientId.
                val lockedEntry = localStoreManager.pendingRequestQueueManager
                    .getPendingRequestById(pendingRequestId) ?: return@withClientLock
                synced = syncUpPendingData(lockedEntry)
            }
            synced
        } catch (e: SyncUpRetryLaterException) {
            throw e
        } catch (e: Exception) {
            val type = entry?.type ?: "unknown"
            val clientId = entry?.data?.clientId ?: "unknown"
            SyncLog.e(TAG, "Error syncing $type for $clientId.", e)
            false
        }
    }

    // Sync Down Region
    private fun upsertServerResultIntoLocal(
        serverObj: O,
        syncedAtTimestamp: String,
    ): UpsertResult {
        // Resolve the canonical client_id by server_id first. If a row already exists
        // for this server_id, we must upsert to that row — never create a duplicate.
        val existingClientId = localStoreManager.getExistingClientId(serverObj.serverId)
        val canonicalClientId = existingClientId ?: serverObj.clientId

        val currentLocalData = localStoreManager.getData(
            clientId = canonicalClientId,
            serverId = serverObj.serverId,
        )
        return if (currentLocalData == null) {
            // If no existing local entry exists for this data, clean add it to the db.
            localStoreManager.upsertEntry(
                serverObj = serverObj,
                syncedAtTimestamp = syncedAtTimestamp,
                clientId = canonicalClientId,
            )
            UpsertResult.CleanUpsert
        } else {
            localStoreManager.upsertSyncDownResponseData(
                clientId = canonicalClientId,
                lastSyncedTimestamp = syncedAtTimestamp,
                updatedServerData = serverObj,
                mergeHandler = rebaseHandler,
            )
        }
    }
    // End region

    // Sync Up Region

    /**
     * @return `true` if the request was successfully uploaded, `false` if it was
     *   skipped (e.g., unresolved placeholders) or failed without throwing.
     */
    private suspend fun syncUpPendingData(row: PendingSyncRequest<O>): Boolean {
        var request = row.request
        // Resolve all placeholders ({serverId}, {version}, {cross:…}) in one pass.
        val serverId = row.baseData?.serverId ?: row.data.serverId
        val version = row.baseData?.version ?: row.data.version
        when (val resolution = request.resolveAllPlaceholders(
            serverId = serverId,
            version = version,
            crossServiceResolver = localStoreManager.crossServiceIdResolver,
        )) {
            is HttpRequest.PlaceholderResolutionResult.Resolved -> request = resolution.request
            is HttpRequest.PlaceholderResolutionResult.UnresolvedServerId -> {
                SyncLog.w(TAG, "Skipping ${row.type} for ${row.data.clientId}: serverId not yet resolved")
                return false
            }
            is HttpRequest.PlaceholderResolutionResult.UnresolvedCrossService -> {
                SyncLog.w(TAG, "Skipping ${row.type} for ${row.data.clientId}: cross-service dependency not yet resolved")
                return false
            }
        }
        return when (val response = serverManager.sendRequest(request)) {
            is ServerManager.ServerManagerResponse.ConnectionError,
            is ServerManager.ServerManagerResponse.RequestTimedOut -> {
                SyncLog.w(TAG, "Sync up failed due to connection error. Trying again later.")
                throw SyncUpRetryLaterException(
                    "Connection error for ${row.type} (${row.data.clientId}, pending_request_id=${row.pendingRequestId})"
                )
            }

            is ServerManager.ServerManagerResponse.ServerError -> {
                SyncLog.w(TAG, "Sync up failed due to server error (${response.statusCode}). Trying again later.")
                if (!row.serverAttemptMade) {
                    localStoreManager.pendingRequestQueueManager.markPendingRequestAsAttempted(row.pendingRequestId)
                }
                SyncLog.w(TAG, "Sync failed for pending_request_id: ${row.pendingRequestId} (${row.type}): ${response.statusCode} — it will be retried later.")
                false
            }

            is ServerManager.ServerManagerResponse.Failed -> {
                SyncLog.w(TAG, "Sync up received non-success status ${response.statusCode}.")
                if (!row.serverAttemptMade) {
                    localStoreManager.pendingRequestQueueManager.markPendingRequestAsAttempted(row.pendingRequestId)
                }
                SyncLog.w(TAG, "Sync failed for pending_request_id: ${row.pendingRequestId} (${row.type}): ${response.statusCode} — it will be retried later.")
                false
            }

            is ServerManager.ServerManagerResponse.Success -> {
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
                    SyncLog.w(TAG, "Sync failed for pending_request_id: ${row.pendingRequestId} (${row.type}): ${response.statusCode} — it will be retried later.")
                    false
                } else {
                    // 3. Parse the response and mark as synced
                    val result = serverProcessingConfig.syncUpConfig.fromResponseBody(
                        requestTag = row.requestTag,
                        responseBody = response.responseBody,
                    )
                    val lastSyncedTimestamp = TimestampFormatter.fromEpochSeconds(response.responseEpochTimestamp)

                    // Clear the queue entry, update the main table, and
                    // propagate server context atomically so concurrent
                    // local writes do not see an inconsistent state.
                    when (result) {
                        is SyncUpResult.Success -> {
                            localStoreManager.upsertPendingRequestSyncResponseData(
                                updatedServerData = result.data,
                                lastSyncedTimestamp = lastSyncedTimestamp,
                                syncedPendingRequest = row,
                                mergeHandler = rebaseHandler,
                            )
                            SyncLog.d(TAG, "Synced ${row.type} for ${row.data.clientId} (server_id=${result.data.serverId})")
                            true
                        }
                        is SyncUpResult.Failed.Retry -> {
                            if (!row.serverAttemptMade) {
                                localStoreManager.pendingRequestQueueManager.markPendingRequestAsAttempted(row.pendingRequestId)
                            }
                            SyncLog.w(TAG, "Sync failed for ${row.type} for ${row.data.clientId} with pending_request_id: ${row.pendingRequestId} — it will be retried later.")
                            throw SyncUpRetryLaterException(
                                "Retry requested for ${row.type} (${row.data.clientId}, pending_request_id=${row.pendingRequestId})"
                            )
                        }
                        is SyncUpResult.Failed.RemovePendingRequest -> {
                            localStoreManager.updateLocalDataAfterPendingRequestSync(
                                processedLocalData = row.data,
                                lastSyncedTimestamp = lastSyncedTimestamp,
                                syncedPendingRequest = row,
                            )
                            SyncLog.w(TAG, "Sync failed for ${row.type} for ${row.data.clientId} with pending_request_id: ${row.pendingRequestId}, pending request is being removed.")
                            false
                        }
                    }
                }
            }
        }
    }
    // End region

    /**
     * Launches a coroutine that periodically calls [syncDownFromServer] at the interval
     * configured by [SyncFetchConfig.syncCadenceSeconds]. The first sync happens immediately.
     * If already running, this is a no-op.
     */
    private fun startPeriodicSyncDown() {
        serviceScope.launch {
            syncJobMutex.withLock {
                if (syncDownJob?.isActive == true) return@launch
                val cadenceMs = serverProcessingConfig.syncFetchConfig.syncCadenceSeconds * 1000L
                syncDownJob = serviceScope.launch {
                    while (isActive) {
                        try {
                            syncDownFromServer()
                        } catch (e: Exception) {
                            SyncLog.e(TAG, "Periodic sync-down failed: ", e)
                        }
                        delay(cadenceMs)
                    }
                }
            }
        }
    }

    /**
     * Stops the periodic sync-down loop. Can be restarted by calling [startPeriodicSyncDown].
     */
    public fun stopPeriodicSyncDown() {
        serviceScope.launch {
            syncJobMutex.withLock {
                syncDownJob?.cancel()
                syncDownJob = null
            }
        }
    }

    public fun close() {
        serviceScope.cancel()
    }

    public companion object {
        public const val TAG: String = "SyncableObjectService:SyncDriver"
    }
}
