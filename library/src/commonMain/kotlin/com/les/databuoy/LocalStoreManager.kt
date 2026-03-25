package com.les.databuoy

import com.les.databuoy.db.SyncDatabase
import kotlin.jvm.Throws

class LocalStoreManager<O : SyncableObject<O>, T : ServiceRequestTag>(
    private val database: SyncDatabase = createSyncDatabase(),
    private val serviceName: String,
    private val syncScheduleNotifier: SyncScheduleNotifier,
    private val codec: SyncCodec<O>,
    private val status: DataBuoyStatus = DataBuoyStatus.shared,
    private val queueStrategy: PendingRequestQueueManager.PendingRequestQueueStrategy =
        PendingRequestQueueManager.PendingRequestQueueStrategy.Queue,
) {
    private fun List<SyncableObjectRebaseHandler.FieldConflict<O>>.toFieldConflictInfo():
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
        strategy = queueStrategy,
        codec = codec,
        status = status,
    )

    /**
     * Runs [block] inside a SQLite transaction using SQLDelight's built-in
     * transaction support. If [block] throws, the transaction is rolled back;
     * otherwise it is committed.
     */
    private fun <T> transaction(block: () -> T): T =
        database.transactionWithResult { block() }

    /**
     * Internal signal used to abort the surrounding database transaction when
     * queueing fails without throwing.
     *
     * The queue layer returns [PendingRequestQueueManager.QueueResult] values for
     * expected failures like invalid requests or storage errors. LocalStoreManager
     * needs those failures to behave transactionally so the `sync_data` write is
     * rolled back if the matching pending queue entry was not stored.
     */
    private class QueueWriteException(
        val queueResult: PendingRequestQueueManager.QueueResult,
    ) : IllegalStateException()

    /**
     * Ensures a queue write succeeded before allowing the enclosing transaction
     * to commit.
     *
     * If queueing returns anything other than [PendingRequestQueueManager.QueueResult.Stored],
     * this throws [QueueWriteException] so the local `sync_data` mutation and its
     * pending queue entry remain all-or-nothing.
     */
    private fun requireQueued(
        queueResult: PendingRequestQueueManager.QueueResult,
    ): PendingRequestQueueManager.QueueResult = when (queueResult) {
        is PendingRequestQueueManager.QueueResult.Stored -> queueResult
        else -> throw QueueWriteException(queueResult)
    }

    fun hasPendingRequests(clientId: String): Boolean =
        pendingRequestQueueManager.hasPendingRequests(clientId)

    /**
     * Returns the effective base data to diff against when computing a sparse update.
     *
     * - **Synced** → the last server-acknowledged snapshot (`last_synced_server_data`).
     * - **PendingCreate / PendingUpdate** → the data from the most recent queued request
     *   (so subsequent offline edits diff against what will be sent, not the original server data).
     * - **LocalOnly / PendingVoid / Conflict / not found** → throws, because an update is
     *   not valid in those states.
     */
    fun getEffectiveBaseDataForUpdate(data: O): O {
        val localStoreEntry = getData(
            clientId = data.clientId,
            serverId = data.serverId,
        )
        return when (localStoreEntry?.syncStatus) {
            is SyncableObject.SyncStatus.LocalOnly ->
                throw Exception("You can't create with an update request.")

            // If the status is pending create or update, there must be a queued request.
            is SyncableObject.SyncStatus.PendingCreate,
            is SyncableObject.SyncStatus.PendingUpdate ->
                pendingRequestQueueManager.getLatestPendingRequest(data.clientId)!!.data

            is SyncableObject.SyncStatus.PendingVoid ->
                throw Exception("Updates are not permitted to voided items")

            is SyncableObject.SyncStatus.Synced -> localStoreEntry.latestServerData!!

            is SyncableObject.SyncStatus.Conflict ->
                throw Exception("Resolve conflicts first. Updates are not permitted in conflict.")

            null -> throw Exception("Failed to find db entry to update.")
        }
    }

    /**
     * Ensures a background sync is scheduled. Call this on service startup so
     * WorkManager picks up any work left from a prior session.
     *
     * This is unconditional because [SyncScheduleNotifier.scheduleSyncIfNeeded]
     * is already cheap and idempotent (Android uses [ExistingWorkPolicy.KEEP]).
     * An extra no-op enqueue is cheaper than a DB query to check first.
     */
    fun scheduleSyncUp() {
        syncScheduleNotifier.scheduleSyncIfNeeded()
    }

    internal fun refreshStatus() {
        status.refresh()
    }

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
                ).let(::requireQueued)
            }

            val updatedData = codec.decode(jsonData, SyncableObject.SyncStatus.PendingCreate)
            SyncLog.d(TAG, "Created data locally and queued upload (client_id: ${data.clientId}).")
            syncScheduleNotifier.scheduleSyncIfNeeded()
            return Pair(updatedData, result)
        } catch (e: QueueWriteException) {
            SyncLog.e(TAG, "Failed to queue async create data for client_id: ${data.clientId}")
            return Pair(data, e.queueResult)
        } catch (e: Exception) {
            SyncLog.e(TAG, "Failed to insert async create data: ", e)
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
            SyncLog.e(TAG, "Failed to insert data from [create] response (server_id: ${serverData.serverId}): ", e)
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
                    sync_status = SyncableObject.SyncStatus.PENDING_UPDATE,
                    service_name = serviceName,
                    client_id = data.clientId,
                )

                pendingRequestQueueManager.queueUpdateRequest(
                    idempotencyKey = idempotencyKey,
                    data = data,
                    lastSyncedData = lastSyncedData,
                    instruction = instruction,
                    requestTag = requestTag,
                ).let(::requireQueued)
            }
            syncScheduleNotifier.scheduleSyncIfNeeded()
            SyncLog.d(TAG, "Updated data locally and queued upload (client_id: ${data.clientId})")
            return Pair(data, result)
        } catch (e: QueueWriteException) {
            SyncLog.e(TAG, "Failed to queue async update data for client_id: ${data.clientId}")
            return Pair(data, e.queueResult)
        } catch (e: Exception) {
            SyncLog.d(TAG, "Failed to update data in db: $e")
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
            SyncLog.e(TAG, "Failed to upsert data from [update] response (server_id: ${serverData.serverId}): $e")
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
                    sync_status = SyncableObject.SyncStatus.PENDING_VOID,
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
                ).let(::requireQueued)
            }
            syncScheduleNotifier.scheduleSyncIfNeeded()
            return Pair(data, result)
        } catch (e: QueueWriteException) {
            SyncLog.e(TAG, "Failed to queue void request for client_id: ${data.clientId}")
            return Pair(data, e.queueResult)
        } catch (e: Exception) {
            SyncLog.e(TAG, "Failed to store void request: ", e)
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
            SyncLog.e(TAG, "Failed to upsert data from [void] response (server_id: ${serverData.serverId}): $e")
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

        SyncLog.d(TAG, "Voided local-only object (client_id: ${data.clientId})")
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
        mergeHandler: SyncableObjectRebaseHandler<O>,
    ): UpsertResult {
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
        mergeHandler: SyncableObjectRebaseHandler<O>,
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
        mergeHandler: SyncableObjectRebaseHandler<O>,
        handleRebasedPendingRequests: (rebasedLocalData: O) -> Unit,
    ): UpsertResult {
        // Update any remaining pending requests with the updated server context.
        val rebaseResult =
            pendingRequestQueueManager.rebaseDataForRemainingPendingRequests(
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
                return UpsertResult.CleanUpsert
            }

            is PendingRequestQueueManager.RebasePendingRequestsResult.RebasedRemainingPendingRequests -> {
                handleRebasedPendingRequests.invoke(rebaseResult.rebasedLatestData)
                return UpsertResult.MergedUpsert
            }

            is PendingRequestQueueManager.RebasePendingRequestsResult.AbortedRebaseToConflicts -> {
                database.syncDataQueries.markConflictAfterRebase(
                    sync_status = SyncableObject.SyncStatus.CONFLICT,
                    last_synced_server_data = codec.encodeToString(updatedServerData),
                    last_synced_timestamp = lastSyncedTimestamp,
                    service_name = serviceName,
                    client_id = clientId,
                )
                return UpsertResult.ConflictFailure
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
                    SyncLog.e(TAG, errorMessage)
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


    /**
     * Resolves a conflict for the given object by:
     * 1. Validating the sync_data entry is in CONFLICT status
     * 2. Finding the conflicting pending request
     * 3. Replacing the pending request with resolved data and a rebuilt HTTP request
     * 4. Re-rebasing any subsequent pending requests
     * 5. Updating sync_data status back to a pending state
     */
    fun resolveConflictData(
        resolvedData: O,
        resolvedHttpRequest: HttpRequest,
        mergeHandler: SyncableObjectRebaseHandler<O>,
    ): ResolveConflictResult<O> {
        val clientId = resolvedData.clientId
        val entry = getData(clientId = clientId, serverId = resolvedData.serverId)
            ?: return ResolveConflictResult.Failed(IllegalStateException("No sync_data entry found for client_id: $clientId"))

        if (entry.syncStatus !is SyncableObject.SyncStatus.Conflict) {
            SyncLog.w(TAG, "sync_data entry for client_id: $clientId is not in CONFLICT status (current: ${entry.syncStatus})")
        }

        val conflictingRequest = pendingRequestQueueManager.getConflictingPendingRequest(clientId)
            ?: return repairOrphanedConflictStatus(
                clientId = clientId,
                serverId = resolvedData.serverId,
                mergeHandler = mergeHandler,
            )

        val newServerBaseline = conflictingRequest.conflict!!.serverValue

        return try {
            val result = database.transactionWithResult {
                // 1. Resolve the conflict on the pending request
                pendingRequestQueueManager.resolveConflictOnPendingRequest(
                    pendingRequest = conflictingRequest,
                    resolvedData = resolvedData,
                    resolvedHttpRequest = resolvedHttpRequest,
                    newServerBaseline = newServerBaseline,
                )

                // 2. Re-rebase any subsequent pending requests on the resolved data.
                //    The just-resolved request was already updated in step 1 (conflict cleared,
                //    data/request replaced), so [rebaseDataForRemainingPendingRequests] will
                //    see it as a clean merge (no diff against resolvedData) and pass through to
                //    the subsequent requests.
                val rebaseResult = pendingRequestQueueManager.rebaseDataForRemainingPendingRequests(
                    clientId = clientId,
                    updatedBaseData = resolvedData,
                    mergeHandler = mergeHandler,
                )

                when (rebaseResult) {
                    is PendingRequestQueueManager.RebasePendingRequestsResult.AbortedRebaseToConflicts -> {
                        // A subsequent pending request conflicts after resolution.
                        // sync_data stays in CONFLICT.
                        return@transactionWithResult ResolveConflictResult.RebaseConflict(
                            conflict = rebaseResult.conflict,
                        )
                    }

                    is PendingRequestQueueManager.RebasePendingRequestsResult.NoPendingRequestRemaining,
                    is PendingRequestQueueManager.RebasePendingRequestsResult.RebasedRemainingPendingRequests -> {
                        val latestData = when (rebaseResult) {
                            is PendingRequestQueueManager.RebasePendingRequestsResult.RebasedRemainingPendingRequests ->
                                rebaseResult.rebasedLatestData
                            else -> resolvedData
                        }

                        // 3. Update sync_data back to a pending state
                        val updatedSyncStatus = when (conflictingRequest.type) {
                            PendingSyncRequest.Type.CREATE -> SyncableObject.SyncStatus.PENDING_CREATE
                            PendingSyncRequest.Type.UPDATE -> SyncableObject.SyncStatus.PENDING_UPDATE
                            PendingSyncRequest.Type.VOID -> SyncableObject.SyncStatus.PENDING_VOID
                        }
                        database.syncDataQueries.resolveConflict(
                            sync_status = updatedSyncStatus,
                            data_blob = codec.encodeToString(latestData),
                            service_name = serviceName,
                            client_id = clientId,
                        )

                        ResolveConflictResult.Resolved(resolvedData = latestData)
                    }
                }
            }
            // Conflict resolved — pending request is now eligible for upload.
            if (result is ResolveConflictResult.Resolved) {
                syncScheduleNotifier.scheduleSyncIfNeeded()
            }
            result
        } catch (e: Exception) {
            SyncLog.e(TAG, "Failed to resolve conflict for client_id: $clientId", e)
            ResolveConflictResult.Failed(e)
        }
    }

    /**
     * Self-heals a sync_data row that is stuck in CONFLICT status when no pending request
     * actually has conflict_info. This is a defensive repair for an inconsistent state that
     * should not normally occur.
     *
     * If pending requests exist, rebases them against each other using the first request's
     * lastSyncedData as the base, then updates sync_data status based on the result.
     * If no pending requests exist, transitions sync_data back to SYNCED.
     *
     * @return [ResolveConflictResult.Resolved] with the current latest data on success,
     *  [ResolveConflictResult.RebaseConflict] if rebasing reveals a real conflict, or
     *  [ResolveConflictResult.Failed] on error.
     */
    fun repairOrphanedConflictStatus(
        clientId: String,
        serverId: String?,
        mergeHandler: SyncableObjectRebaseHandler<O>,
    ): ResolveConflictResult<O> {
        val entry = getData(clientId = clientId, serverId = serverId)
            ?: return ResolveConflictResult.Failed(
                IllegalStateException("No sync_data entry found for client_id: $clientId")
            )

        // If the row is not in CONFLICT status, it's already fine — return current data.
        if (entry.syncStatus !is SyncableObject.SyncStatus.Conflict) {
            return ResolveConflictResult.Resolved(resolvedData = entry.data)
        }

        val pendingRequests = pendingRequestQueueManager.getPendingRequests(clientId)

        return try {
            val result = database.transactionWithResult {
                if (pendingRequests.isEmpty()) {
                    // No pending requests — just restore to SYNCED.
                    database.syncDataQueries.resolveConflict(
                        sync_status = SyncableObject.SyncStatus.SYNCED,
                        data_blob = codec.encodeToString(entry.data),
                        service_name = serviceName,
                        client_id = clientId,
                    )
                    return@transactionWithResult ResolveConflictResult.Resolved(resolvedData = entry.data)
                }

                // Use the first pending request's lastSyncedData as the base for rebasing.
                val baseData = pendingRequests.first().lastSyncedData ?: entry.latestServerData
                if (baseData == null) {
                    // No server baseline available — use the first pending request's data as-is.
                    val latestData = pendingRequests.last().data
                    val updatedSyncStatus = when (pendingRequests.first().type) {
                        PendingSyncRequest.Type.CREATE -> SyncableObject.SyncStatus.PENDING_CREATE
                        PendingSyncRequest.Type.UPDATE -> SyncableObject.SyncStatus.PENDING_UPDATE
                        PendingSyncRequest.Type.VOID -> SyncableObject.SyncStatus.PENDING_VOID
                    }
                    database.syncDataQueries.resolveConflict(
                        sync_status = updatedSyncStatus,
                        data_blob = codec.encodeToString(latestData),
                        service_name = serviceName,
                        client_id = clientId,
                    )
                    return@transactionWithResult ResolveConflictResult.Resolved(resolvedData = latestData)
                }

                // Rebase all pending requests on the base data to verify no real conflicts.
                val rebaseResult = pendingRequestQueueManager.rebaseDataForRemainingPendingRequests(
                    clientId = clientId,
                    updatedBaseData = baseData,
                    mergeHandler = mergeHandler,
                )

                when (rebaseResult) {
                    is PendingRequestQueueManager.RebasePendingRequestsResult.AbortedRebaseToConflicts -> {
                        // Rebasing revealed a real conflict — sync_data stays in CONFLICT.
                        return@transactionWithResult ResolveConflictResult.RebaseConflict(
                            conflict = rebaseResult.conflict,
                        )
                    }

                    is PendingRequestQueueManager.RebasePendingRequestsResult.NoPendingRequestRemaining -> {
                        database.syncDataQueries.resolveConflict(
                            sync_status = SyncableObject.SyncStatus.SYNCED,
                            data_blob = codec.encodeToString(entry.data),
                            service_name = serviceName,
                            client_id = clientId,
                        )
                        ResolveConflictResult.Resolved(resolvedData = entry.data)
                    }

                    is PendingRequestQueueManager.RebasePendingRequestsResult.RebasedRemainingPendingRequests -> {
                        val latestData = rebaseResult.rebasedLatestData
                        val updatedSyncStatus = when (pendingRequests.first().type) {
                            PendingSyncRequest.Type.CREATE -> SyncableObject.SyncStatus.PENDING_CREATE
                            PendingSyncRequest.Type.UPDATE -> SyncableObject.SyncStatus.PENDING_UPDATE
                            PendingSyncRequest.Type.VOID -> SyncableObject.SyncStatus.PENDING_VOID
                        }
                        database.syncDataQueries.resolveConflict(
                            sync_status = updatedSyncStatus,
                            data_blob = codec.encodeToString(latestData),
                            service_name = serviceName,
                            client_id = clientId,
                        )
                        ResolveConflictResult.Resolved(resolvedData = latestData)
                    }
                }
            }
            // Conflict repaired — pending requests are now eligible for upload.
            if (result is ResolveConflictResult.Resolved) {
                syncScheduleNotifier.scheduleSyncIfNeeded()
            }
            result
        } catch (e: Exception) {
            SyncLog.e(TAG, "Failed to repair orphaned conflict for client_id: $clientId", e)
            ResolveConflictResult.Failed(e)
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
