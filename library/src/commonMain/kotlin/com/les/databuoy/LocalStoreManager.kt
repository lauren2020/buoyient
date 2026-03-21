package com.les.databuoy

import com.les.databuoy.db.SyncDatabase
import kotlin.jvm.Throws

class LocalStoreManager<O : SyncableObject<O>, T : ServiceRequestTag>(
    private val database: SyncDatabase = createSyncDatabase(),
    private val serviceName: String,
    private val syncScheduleNotifier: SyncScheduleNotifier,
    private val codec: SyncCodec<O>,
    private val logger: SyncLogger,
) {
    private fun List<SyncableObjectMergeHandler.FieldConflict<O>>.toFieldConflictInfo():
        List<SyncableObject.SyncStatus.Conflict.FieldConflictInfo> = flatMap { fieldConflict ->
        fieldConflict.fieldNames.map { fieldName ->
            SyncableObject.SyncStatus.Conflict.FieldConflictInfo(
                fieldName = fieldName,
                baseValue = fieldConflict.baseValue?.let { codec.encodeToString(it) },
                localValue = codec.encodeToString(fieldConflict.localValue),
                serverValue = codec.encodeToString(fieldConflict.serverValue),
            )
        }
    }

    internal val pendingRequestQueueManager: PendingRequestQueueManager<O, T> = PendingRequestQueueManager(
        database = database,
        serviceName = serviceName,
        strategy = PendingRequestQueueManager.PendingRequestQueueStrategy.Queue,
        codec = codec,
        logger = logger,
    )

    /**
     * Runs [block] inside a SQLite transaction using SQLDelight's built-in
     * transaction support. If [block] throws, the transaction is rolled back;
     * otherwise it is committed.
     */
    private fun <T> transaction(block: () -> T): T =
        database.transactionWithResult { block() }

    fun hasPendingRequests(clientId: String): Boolean =
        pendingRequestQueueManager.hasPendingRequests(clientId)

    fun close() {
        // No-op: the database is application-scoped and outlives any
        // individual service instance. Android tears down the connection
        // when the process is destroyed.
    }

    /**
     * Queue up a CREATE request to be processed async.
     */
    fun insertLocalData(
        data: O,
        httpRequest: HttpRequest,
        idempotencyKey: String,
        requestTag: T,
        serverAttemptMade: Boolean = false,
    ): Pair<O, PendingRequestQueueManager.QueueResult> {
        try {
            val jsonData = codec.encode(data)
            val result = transaction {
                database.syncDataQueries.insertLocalData(
                    service_name = serviceName,
                    client_id = data.clientId,
                    server_id = data.serverId,
                    version = data.version.toLong(),
                    data_blob = jsonData.toString(),
                    sync_status = SyncableObject.SyncStatus.PENDING_CREATE,
                )

                pendingRequestQueueManager.queueCreateRequest(
                    data = data,
                    httpRequest = httpRequest,
                    idempotencyKey = idempotencyKey,
                    serverAttemptMade = serverAttemptMade,
                    requestTag = requestTag,
                )
            }

            val updatedData = codec.decode(jsonData, SyncableObject.SyncStatus.PendingCreate)
            logger.d(TAG, "Created data locally and queued upload (client_id: ${data.clientId}).")
            syncScheduleNotifier.scheduleSyncIfNeeded()
            return Pair(updatedData, result)
        } catch (e: Exception) {
            logger.e(TAG, "Failed to insert async create data: ", e)
            return Pair(data, PendingRequestQueueManager.QueueResult.StoreFailed)
        }
    }

    /**
     * Persist the data back from the server after processing a CREATE request synchronously with
     * the server while online.
     */
    fun insertFromServerResponse(
        serverData: O,
        responseTimestamp: String,
    ) {
        try {
            val serverDataJson = codec.encodeToString(serverData)
            database.syncDataQueries.insertFromServerResponse(
                service_name = serviceName,
                client_id = serverData.clientId,
                server_id = serverData.serverId,
                version = serverData.version.toLong(),
                last_synced_timestamp = responseTimestamp,
                data_blob = serverDataJson,
                sync_status = SyncableObject.SyncStatus.SYNCED,
                last_synced_server_data = serverDataJson,
            )
        } catch (e: Exception) {
            logger.e(TAG, "Failed to insert data from [create] response (server_id: ${serverData.serverId}): ", e)
        }
    }

    /**
     * Queue up an UPDATE request to be processed async.
     */
    fun updateLocalData(
        data: O,
        idempotencyKey: String,
        lastSyncedData: O,
        instruction: PendingRequestQueueManager.UpdateQueueInstruction<O>,
        requestTag: T,
    ): Pair<O, PendingRequestQueueManager.QueueResult> {
        try {
            val result = transaction {
                database.syncDataQueries.updateLocalData(
                    version = data.version.toLong(),
                    data_blob = codec.encode(data).toString(),
                    service_name = serviceName,
                    client_id = data.clientId,
                )

                pendingRequestQueueManager.queueUpdateRequest(
                    idempotencyKey = idempotencyKey,
                    data = data,
                    lastSyncedData = lastSyncedData,
                    instruction = instruction,
                    requestTag = requestTag,
                )
            }
            syncScheduleNotifier.scheduleSyncIfNeeded()
            logger.d(TAG, "Updated data locally and queued upload (client_id: ${data.clientId})")
            return Pair(data, result)
        } catch (e: Exception) {
            logger.d(TAG, "Failed to update data in db: $e")
            return Pair(data, PendingRequestQueueManager.QueueResult.StoreFailed)
        }
    }

    /**
     * Persist the data back from the server after processing an UPDATE request synchronously with
     * the server while online.
     */
    fun upsertFromServerResponse(serverData: O, responseTimestamp: String) {
        try {
            val serverDataJson = codec.encodeToString(serverData)
            database.syncDataQueries.upsertFromServerResponse(
                last_synced_timestamp = responseTimestamp,
                version = serverData.version.toLong(),
                sync_status = SyncableObject.SyncStatus.SYNCED,
                data_blob = serverDataJson,
                last_synced_server_data = serverDataJson,
                service_name = serviceName,
                client_id = serverData.clientId,
            )
        } catch (e: Exception) {
            logger.e(TAG, "Failed to upsert data from [update] response (server_id: ${serverData.serverId}): $e")
        }
    }

    fun upsertEntry(
        serverObj: O,
        syncedAtTimestamp: String,
        clientId: String,
    ) {
        val serverDataJson = codec.encodeToString(serverObj)
        database.syncDataQueries.upsertEntry(
            service_name = serviceName,
            client_id = clientId,
            server_id = serverObj.serverId,
            version = serverObj.version.toLong(),
            last_synced_timestamp = syncedAtTimestamp,
            data_blob = serverDataJson,
            sync_status = SyncableObject.SyncStatus.SYNCED,
            last_synced_server_data = serverDataJson,
        )
    }

    fun voidData(
        data: O,
        httpRequest: HttpRequest,
        idempotencyKey: String,
        requestTag: T,
        serverAttemptMade: Boolean = false,
    ): Pair<O, PendingRequestQueueManager.QueueResult> {
        try {
            val result = transaction {
                database.syncDataQueries.markVoided(
                    service_name = serviceName,
                    client_id = data.clientId,
                )

                pendingRequestQueueManager.queueVoidRequest(
                    data = data,
                    httpRequest = httpRequest,
                    idempotencyKey = idempotencyKey,
                    serverAttemptMade = serverAttemptMade,
                    lastSyncedServerData = null,
                    requestTag = requestTag,
                )
            }
            syncScheduleNotifier.scheduleSyncIfNeeded()
            return Pair(data, result)
        } catch (e: Exception) {
            logger.e(TAG, "Failed to store void request: ", e)
            return Pair(data, PendingRequestQueueManager.QueueResult.StoreFailed)
        }
    }

    fun upsertFromVoidServerResponse(serverData: O, responseTimestamp: String) {
        try {
            val serverDataJson = codec.encodeToString(serverData)
            database.syncDataQueries.upsertFromVoidServerResponse(
                last_synced_timestamp = responseTimestamp,
                version = serverData.version.toLong(),
                sync_status = SyncableObject.SyncStatus.SYNCED,
                data_blob = serverDataJson,
                last_synced_server_data = serverDataJson,
                service_name = serviceName,
                client_id = serverData.clientId,
            )
        } catch (e: Exception) {
            logger.e(TAG, "Failed to upsert data from [void] response (server_id: ${serverData.serverId}): $e")
        }
    }

    @Throws
    fun voidLocalOnlyData(data: O): O {
        val jsonData = codec.encode(data)
        transaction {
            database.syncDataQueries.voidLocalOnly(
                sync_status = SyncableObject.SyncStatus.LOCAL_ONLY,
                data_blob = jsonData.toString(),
                service_name = serviceName,
                client_id = data.clientId,
            )
            pendingRequestQueueManager.clearAllPendingRequests(data.clientId)
        }

        logger.d(TAG, "Voided local-only object (client_id: ${data.clientId})")
        return data.withSyncStatus(SyncableObject.SyncStatus.LocalOnly)
    }

    /**
     * @param rebasedLatestData - rebased latest data if rebase was applied, null if no rebase
     *  was needed.
     */
    private fun updatePendingUploadEntryAsUploaded(
        row: PendingSyncRequest<O>,
        rebasedLatestData: O?,
        latestServerData: O,
        lastSyncedTimestamp: String,
        updatedSyncStatus: String,
    ) {
        val resolvedDataJson = codec.encodeToString(latestServerData)
        when (row.type) {
            PendingSyncRequest.Type.CREATE,
            PendingSyncRequest.Type.UPDATE -> {
                if (rebasedLatestData != null) {
                    database.syncDataQueries.updateAfterCreateOrUpdateUpload(
                        server_id = latestServerData.serverId,
                        last_synced_timestamp = lastSyncedTimestamp,
                        version = latestServerData.version.toLong(),
                        sync_status = updatedSyncStatus,
                        data_blob = codec.encodeToString(rebasedLatestData),
                        last_synced_server_data = resolvedDataJson,
                        service_name = serviceName,
                        client_id = row.data.clientId,
                    )
                } else {
                    database.syncDataQueries.updateAfterCreateOrUpdateUploadWithoutData(
                        server_id = latestServerData.serverId,
                        last_synced_timestamp = lastSyncedTimestamp,
                        version = latestServerData.version.toLong(),
                        sync_status = updatedSyncStatus,
                        last_synced_server_data = resolvedDataJson,
                        service_name = serviceName,
                        client_id = row.data.clientId,
                    )
                }
            }

            PendingSyncRequest.Type.VOID -> {
                database.syncDataQueries.updateAfterVoidUpload(
                    last_synced_timestamp = lastSyncedTimestamp,
                    sync_status = updatedSyncStatus,
                    data_blob = resolvedDataJson,
                    last_synced_server_data = resolvedDataJson,
                    version = latestServerData.version.toLong(),
                    service_name = serviceName,
                    client_id = row.data.clientId,
                )
            }
        }
    }

    fun getData(clientId: String, serverId: String?): LocalStoreEntry<O>? {
        val rows = database.syncDataQueries.getData(
            service_name = serviceName,
            client_id = clientId,
            server_id = serverId,
        ).executeAsList()

        val row = rows.firstOrNull() ?: return null

        val statusValue = row.sync_status
        val lastSyncedTimestamp = row.last_synced_timestamp
        val syncStatus = SyncableObject.SyncStatus.buildFromDbContext(
            status = statusValue,
            lastSyncedTimestamp = lastSyncedTimestamp,
            conflictInfo = if (statusValue == SyncableObject.SyncStatus.CONFLICT) {
                pendingRequestQueueManager.getConflicts(clientId = row.client_id).toFieldConflictInfo()
            } else {
                emptyList()
            },
        )
        val lastSyncedServerData = row.last_synced_server_data?.let {
            codec.decode(it, SyncableObject.SyncStatus.Synced(lastSyncedTimestamp = lastSyncedTimestamp!!))
        }
        val latestLocalData = codec.decode(row.data_blob, syncStatus)

        return LocalStoreEntry(
            data = latestLocalData,
            latestServerData = lastSyncedServerData,
            lastSyncedTimestamp = lastSyncedTimestamp,
            syncStatus = syncStatus,
        )
    }

    fun getAllData(limit: Int): List<LocalStoreEntry<O>> {
        val rows = database.syncDataQueries.getAllData(
            service_name = serviceName,
            limit = limit.toLong(),
        ).executeAsList()

        return rows.map { row ->
            val statusValue = row.sync_status
            val lastSyncedTimestamp = row.last_synced_timestamp
            val latestSyncStatus = SyncableObject.SyncStatus.buildFromDbContext(
                status = statusValue,
                lastSyncedTimestamp = lastSyncedTimestamp,
                conflictInfo = if (statusValue == SyncableObject.SyncStatus.CONFLICT) {
                    pendingRequestQueueManager.getConflicts(clientId = row.client_id).toFieldConflictInfo()
                } else {
                    emptyList()
                },
            )
            val data = codec.decode(row.data_blob, latestSyncStatus)
            val latestServerData = row.last_synced_server_data?.let {
                codec.decode(it, SyncableObject.SyncStatus.Synced(lastSyncedTimestamp!!))
            }
            LocalStoreEntry(
                data = data,
                latestServerData = latestServerData,
                lastSyncedTimestamp = lastSyncedTimestamp,
                syncStatus = latestSyncStatus,
            )
        }
    }

    /**
     * When we sync down from the server and receive updates to a data object we are tracking
     * locally, we need to upsert that new server data into the local store and rebase any
     * potential pending upload requests.
     */
    fun upsertSyncDownResponseData(
        clientId: String,
        lastSyncedTimestamp: String,
        updatedServerData: O,
        mergeHandler: SyncableObjectMergeHandler<O>,
    ): SyncDriver.UpsertResult {
        return database.transactionWithResult {
            return@transactionWithResult rebaseData(
                clientId = clientId,
                lastSyncedTimestamp = lastSyncedTimestamp,
                updatedServerData = updatedServerData,
                mergeHandler = mergeHandler,
            ) { rebasedLocalData ->
                // Update the sync_data table data entry.
                database.syncDataQueries.updateAfterCreateOrUpdateUpload(
                    server_id = updatedServerData.serverId,
                    last_synced_timestamp = lastSyncedTimestamp,
                    version = updatedServerData.version.toLong(),
                    sync_status = SyncableObject.SyncStatus.SYNCED,
                    data_blob = codec.encodeToString(rebasedLocalData),
                    last_synced_server_data = codec.encodeToString(updatedServerData),
                    service_name = serviceName,
                    client_id = clientId,
                )
            }
        }
    }

    /**
     * When we sync pending requests up to the server & get a response back from the server,
     * the server data returned in the response from that sync up needs to be upserted into our
     * local data store and all pending sync requests need to be rebased on that updated server
     * data. This function handles that.
     */
    fun upsertPendingRequestSyncResponseData(
        updatedServerData: O,
        lastSyncedTimestamp: String,
        syncedPendingRequest: PendingSyncRequest<O>,
        mergeHandler: SyncableObjectMergeHandler<O>,
    ) {
        transaction {
            when (
                val clearResult = pendingRequestQueueManager.clearPendingRequestAfterUpload(
                    pendingRequestId = syncedPendingRequest.pendingRequestId,
                    clientId = syncedPendingRequest.data.clientId,
                )
            ) {
                is PendingRequestQueueManager.ClearRequestResult.FailedToRemoveEntry ->
                    throw IllegalStateException("Failed to remove pending sync entry, try again after it is idempotently retried.")

                is PendingRequestQueueManager.ClearRequestResult.Cleared -> {
                    rebaseData(
                        clientId = syncedPendingRequest.data.clientId,
                        lastSyncedTimestamp = lastSyncedTimestamp,
                        updatedServerData = updatedServerData,
                        mergeHandler = mergeHandler,
                    ) { rebasedLocalData ->
                        // Update the sync_data table data entry.
                        updatePendingUploadEntryAsUploaded(
                            row = syncedPendingRequest,
                            rebasedLatestData = rebasedLocalData,
                            latestServerData = updatedServerData,
                            lastSyncedTimestamp = lastSyncedTimestamp,
                            updatedSyncStatus = clearResult.updatedSyncStatus,
                        )
                    }
                }
            }
        }
    }

    private fun rebaseData(
        clientId: String,
        lastSyncedTimestamp: String,
        updatedServerData: O,
        mergeHandler: SyncableObjectMergeHandler<O>,
        handleRebasedPendingRequests: (rebasedLocalData: O) -> Unit,
    ): SyncDriver.UpsertResult {
        // Update any remaining pending requests with the updated server context.
        val rebaseResult =
            pendingRequestQueueManager.rebaseServerDataForRemainingPendingRequests(
                clientId = clientId,
                updatedBaseData = updatedServerData,
                mergeHandler = mergeHandler,
            )
        when (rebaseResult) {
            is PendingRequestQueueManager.RebasePendingRequestsResult.NoPendingRequestRemaining -> {
                // This data cleared the server and was returned & there are no further
                // pending changes so there is no need to merge any data - just upsert
                // the server data as the latest version for this entry.
                upsertEntry(
                    serverObj = updatedServerData,
                    syncedAtTimestamp = lastSyncedTimestamp,
                    clientId = clientId,
                )
                return SyncDriver.UpsertResult.CleanUpsert
            }

            is PendingRequestQueueManager.RebasePendingRequestsResult.RebasedRemainingPendingRequests -> {
                handleRebasedPendingRequests.invoke(rebaseResult.rebasedLatestData)
                return SyncDriver.UpsertResult.MergedUpsert
            }

            is PendingRequestQueueManager.RebasePendingRequestsResult.AbortedRebaseToConflicts -> {
                database.syncDataQueries.markConflictAfterRebase(
                    sync_status = SyncableObject.SyncStatus.CONFLICT,
                    last_synced_server_data = codec.encodeToString(updatedServerData),
                    last_synced_timestamp = lastSyncedTimestamp,
                    service_name = serviceName,
                    client_id = clientId,
                )
                return SyncDriver.UpsertResult.ConflictFailure
            }
        }
    }

    fun updateLocalDataAfterPendingRequestSync(
        processedLocalData: O,
        lastSyncedTimestamp: String,
        syncedPendingRequest: PendingSyncRequest<O>,
    ) {
        transaction {
            when (
                val clearResult = pendingRequestQueueManager.clearPendingRequestAfterUpload(
                    pendingRequestId = syncedPendingRequest.pendingRequestId,
                    clientId = syncedPendingRequest.data.clientId,
                )
            ) {
                is PendingRequestQueueManager.ClearRequestResult.FailedToRemoveEntry -> {
                    val errorMessage = "Failed to remove pending sync entry, try again after it is idempotently retried."
                    logger.e(TAG, errorMessage)
                    throw IllegalStateException(errorMessage)
                }

                is PendingRequestQueueManager.ClearRequestResult.Cleared -> {
                    // Update the sync_data table data entry.
                    updatePendingUploadEntryAsUploaded(
                        row = syncedPendingRequest,
                        // We did not get any data back from the server so there is no data to rebase.
                        rebasedLatestData = null,
                        latestServerData = processedLocalData,
                        lastSyncedTimestamp = lastSyncedTimestamp,
                        updatedSyncStatus = clearResult.updatedSyncStatus,
                    )
                }
            }
        }
    }

    class LocalStoreEntry<O>(
        val data: O,
        val latestServerData: O?,
        val lastSyncedTimestamp: String?,
        val syncStatus: SyncableObject.SyncStatus,
    )

    companion object {
        const val TAG = "SyncableObjectService:LocalStoreManager"
    }
}
