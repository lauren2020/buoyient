package com.elvdev.buoyient.managers

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import com.elvdev.buoyient.globalconfigs.DatabaseOverride
import com.elvdev.buoyient.serviceconfigs.EncryptionProvider
import com.elvdev.buoyient.datatypes.Filter
import com.elvdev.buoyient.datatypes.HttpRequest
import com.elvdev.buoyient.datatypes.PageCursor
import com.elvdev.buoyient.serviceconfigs.PagingConfig
import com.elvdev.buoyient.serviceconfigs.PendingRequestQueueStrategy
import com.elvdev.buoyient.datatypes.ResolveConflictResult
import com.elvdev.buoyient.ServiceRequestTag
import com.elvdev.buoyient.datatypes.SquashRequestMerger
import com.elvdev.buoyient.utils.StorageCodec
import com.elvdev.buoyient.utils.SyncCodec
import com.elvdev.buoyient.utils.BuoyientLog
import com.elvdev.buoyient.sync.SyncScheduleNotifier
import com.elvdev.buoyient.SyncableObject
import com.elvdev.buoyient.serviceconfigs.SyncableObjectRebaseHandler
import com.elvdev.buoyient.sync.UpsertResult
import com.elvdev.buoyient.globalconfigs.createSyncDatabase
import com.elvdev.buoyient.db.SyncDatabase
import com.elvdev.buoyient.utils.ioDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map

internal class LocalStoreManager<O : SyncableObject<O>, T : ServiceRequestTag>(
    private val database: SyncDatabase = DatabaseOverride.database ?: createSyncDatabase(),
    private val serviceName: String,
    private val syncScheduleNotifier: SyncScheduleNotifier,
    private val codec: SyncCodec<O>,
    private val queueStrategy: PendingRequestQueueStrategy =
        PendingRequestQueueStrategy.Queue,
    encryptionProvider: EncryptionProvider? = null,
    private val pagingConfig: PagingConfig<O> = PagingConfig(keyExtractor = { it.clientId }),
    /**
     * Raw [SqlDriver] used by dynamic-SQL paths (filter queries, expression-index
     * creation). Not required for ordinary CRUD; null is fine when the consumer
     * does not use filter-based [getPage] calls.
     */
    private val driver: SqlDriver? = DatabaseOverride.driver,
    /**
     * JSON paths to be backed by SQLite expression indexes for fast filter queries.
     * Indexes are created lazily on first use (see [ensureIndexedPaths]).
     */
    private val indexedJsonPaths: List<String> = emptyList(),
) {
    private val storageCodec = StorageCodec(encryptionProvider)

    /**
     * Emits a `Unit` tick whenever this service's `sync_data` rows are written
     * (sync-down inserts, sync-up merges, local create/update/void, conflict
     * resolution). Hot, conflated — subscribers should treat each tick as a
     * "something changed" signal and re-query if they care about the contents.
     */
    private val _localStoreChanges = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    internal val localStoreChanges: SharedFlow<Unit> = _localStoreChanges.asSharedFlow()

    private fun notifyLocalStoreChanged() {
        _localStoreChanges.tryEmit(Unit)
    }

    private fun O.toPagingKey(): String = pagingConfig.keyExtractor(this)
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

    /**
     * Resolves a cross-service placeholder by looking up the server ID for a
     * given (serviceName, clientId) pair in the shared `sync_data` table.
     * Returns `null` if the referenced object hasn't been assigned a server ID yet.
     */
    internal val crossServiceIdResolver: (String, String) -> String? = { svcName, clientId ->
        database.syncDataQueries
            .getServerIdByServiceAndClientId(svcName, clientId)
            .executeAsOneOrNull()
            ?.server_id
    }

    internal val pendingRequestQueueManager: PendingRequestQueueManager<O, T> =
        PendingRequestQueueManager(
            database = database,
            serviceName = serviceName,
            strategy = queueStrategy,
            codec = codec,
            storageCodec = storageCodec,
        )

    /**
     * Runs [block] inside a SQLite transaction using SQLDelight's built-in
     * transaction support. If [block] throws, the transaction is rolled back;
     * otherwise it is committed.
     */
    private fun <T> transaction(block: () -> T): T {
        val result = database.transactionWithResult { block() }
        notifyLocalStoreChanged()
        return result
    }

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

    internal fun hasPendingRequests(clientId: String): Boolean =
        pendingRequestQueueManager.hasPendingRequests(clientId)

    internal fun getEffectiveUpdateContext(updatedData: O): UpdateContext<O> {
        try {
            val pendingRequests = pendingRequestQueueManager.getPendingRequests(updatedData.clientId)
            val latestPendingRequest = pendingRequests.lastOrNull()
            when (pendingRequestQueueManager.strategy) {
                is PendingRequestQueueStrategy.Squash -> {
                    if (latestPendingRequest?.serverAttemptMade == true) {
                        val effectiveBaseData = getEffectiveBaseDataForUpdate(
                            data = updatedData,
                            // Override the preferred strategy since we are forcing queue.
                            effectiveStrategy = PendingRequestQueueStrategy.Queue,
                        )
                        // If a server attempt was already made and we do not know if the server
                        // received that request or not, we want to make sure that any retry of
                        // that data is idempotent so we should not squash that request.
                        return UpdateContext.ValidUpdate.Queue.ForcedAfterServerAttempt(
                            baseData = effectiveBaseData,
                            hasPendingRequests = pendingRequests.isNotEmpty(),
                        )
                    } else {
                        val effectiveBaseData = getEffectiveBaseDataForUpdate(
                            data = updatedData,
                            effectiveStrategy = pendingRequestQueueManager.strategy,
                        )
                        return UpdateContext.ValidUpdate.Squash(
                            baseData = effectiveBaseData,
                            hasPendingRequests = pendingRequests.isNotEmpty(),
                            squashUpdateIntoCreate = pendingRequestQueueManager.strategy.squashUpdateIntoCreate,
                        )
                    }
                }

                is PendingRequestQueueStrategy.Queue -> {
                    val effectiveBaseData = getEffectiveBaseDataForUpdate(
                        data = updatedData,
                        effectiveStrategy = pendingRequestQueueManager.strategy,
                    )
                    return UpdateContext.ValidUpdate.Queue.Preferred(
                        baseData = effectiveBaseData,
                        hasPendingRequests = pendingRequests.isNotEmpty(),
                    )
                }
            }
        } catch (e: Exception) {
            return UpdateContext.InvalidState<O>()
        }
    }

    internal sealed class UpdateContext<O : SyncableObject<O>> {
        internal sealed class ValidUpdate<O : SyncableObject<O>>(
            internal val baseData: O,
            internal val hasPendingRequests: Boolean,
        ) : UpdateContext<O>() {
            internal sealed class Queue<O : SyncableObject<O>>(
                baseData: O,
                hasPendingRequests: Boolean,
            ) : ValidUpdate<O>(baseData, hasPendingRequests) {
                internal class Preferred<O : SyncableObject<O>>(
                    baseData: O,
                    hasPendingRequests: Boolean,
                ) : Queue<O>(baseData, hasPendingRequests)

                internal class ForcedAfterServerAttempt<O : SyncableObject<O>>(
                    baseData: O,
                    hasPendingRequests: Boolean,
                ) : Queue<O>(baseData, hasPendingRequests)
            }

            internal class Squash<O : SyncableObject<O>>(
                baseData: O,
                hasPendingRequests: Boolean,
                internal val squashUpdateIntoCreate: SquashRequestMerger,
            ) : ValidUpdate<O>(baseData, hasPendingRequests)
        }

        internal class InvalidState<O : SyncableObject<O>> : UpdateContext<O>()
    }

    /**
     * Returns the effective base data to diff against when computing a sparse update.
     *
     * - **Synced** → the last server-acknowledged snapshot (`last_synced_server_data`).
     * - **PendingCreate / PendingUpdate** → the data from the most recent queued request
     *   (so subsequent offline edits diff against what will be sent, not the original server data).
     * - **LocalOnly / PendingVoid / Conflict / not found** → throws, because an update is
     *   not valid in those states.
     */
    internal fun getEffectiveBaseDataForUpdate(
        data: O,
        effectiveStrategy: PendingRequestQueueStrategy,
    ): O {
        val localStoreEntry = getData(
            clientId = data.clientId,
            serverId = data.serverId,
        )
        return when (localStoreEntry?.syncStatus) {
            is SyncableObject.SyncStatus.LocalOnly ->
                throw Exception("You can't create with an update request.")

            // If the status is pending create or update, there must be a queued request.
            is SyncableObject.SyncStatus.PendingCreate -> {
                // If we have a pending create, baseData should be null so the effective
                // base data is always the latest data from the last request regardless of strategy.
                // The create + update request squasher will handled blending the 2 if needed.
                pendingRequestQueueManager.getLatestPendingRequest(data.clientId)!!.data
            }

            is SyncableObject.SyncStatus.PendingUpdate -> when (effectiveStrategy) {
                is PendingRequestQueueStrategy.Queue -> {
                    pendingRequestQueueManager.getLatestPendingRequest(data.clientId)!!.data
                }
                is PendingRequestQueueStrategy.Squash -> {
                    pendingRequestQueueManager.getLatestPendingRequest(data.clientId)!!.baseData!!
                }
            }

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
    internal fun scheduleSyncUp() {
        syncScheduleNotifier.scheduleSyncIfNeeded()
    }


    internal fun close() {
        // No-op: the database is application-scoped and outlives any
        // individual service instance. Android tears down the connection
        // when the process is destroyed.
    }

    /**
     * Queue up a CREATE request to be processed async.
     */
    internal fun insertLocalData(
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
                    version = data.version,
                    data_blob = storageCodec.encodeForStorage(jsonData.toString()),
                    sync_status = SyncableObject.SyncStatus.PENDING_CREATE,
                    paging_key = data.toPagingKey(),
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
            BuoyientLog.d(TAG, "Created data locally and queued upload (client_id: ${data.clientId}).")
            syncScheduleNotifier.scheduleSyncIfNeeded()
            return Pair(updatedData, result)
        } catch (e: QueueWriteException) {
            BuoyientLog.e(TAG, "Failed to queue async create data for client_id: ${data.clientId}")
            return Pair(data, e.queueResult)
        } catch (e: Exception) {
            BuoyientLog.e(TAG, "Failed to insert async create data: ", e)
            return Pair(data, PendingRequestQueueManager.QueueResult.StoreFailed)
        }
    }

    /**
     * Persist the data back from the server after processing a CREATE request synchronously with
     * the server while online.
     *
     * @param originalClientId the client_id that originated the request — used as the SQL key
     *   regardless of what [serverData.clientId] contains, so the server cannot change our
     *   primary key.
     */
    internal fun insertFromServerResponse(
        serverData: O,
        responseTimestamp: String,
        originalClientId: String,
    ) {
        try {
            val serverDataJson = codec.encodeToString(serverData)
            val encryptedServerDataJson = storageCodec.encodeForStorage(serverDataJson)
            // Use upsertEntry (INSERT … ON CONFLICT DO UPDATE) so that if a sync-down
            // already inserted a row for this client_id, we update it instead of crashing.
            database.syncDataQueries.upsertEntry(
                service_name = serviceName,
                client_id = originalClientId,
                server_id = serverData.serverId,
                version = serverData.version,
                last_synced_timestamp = responseTimestamp,
                data_blob = encryptedServerDataJson,
                sync_status = SyncableObject.SyncStatus.SYNCED,
                last_synced_server_data = encryptedServerDataJson,
                paging_key = serverData.toPagingKey(),
            )
            notifyLocalStoreChanged()
        } catch (e: Exception) {
            BuoyientLog.e(TAG, "Failed to insert data from [create] response (server_id: ${serverData.serverId}): ", e)
        }
    }

    /**
     * Queue up an UPDATE request to be processed async.
     */
    internal fun updateLocalData(
        data: O,
        idempotencyKey: String,
        updateRequest: HttpRequest,
        serverAttemptMadeForCurrentRequest: Boolean,
        updateContext: UpdateContext.ValidUpdate<O>,
        requestTag: T,
    ): Pair<O, PendingRequestQueueManager.QueueResult> {
        try {
            val result = transaction {
                database.syncDataQueries.updateLocalData(
                    version = data.version,
                    data_blob = storageCodec.encodeForStorage(codec.encode(data).toString()),
                    sync_status = SyncableObject.SyncStatus.PENDING_UPDATE,
                    paging_key = data.toPagingKey(),
                    service_name = serviceName,
                    client_id = data.clientId,
                )

                pendingRequestQueueManager.queueUpdateRequest(
                    idempotencyKey = idempotencyKey,
                    data = data,
                    updateRequest = updateRequest,
                    serverAttemptMadeForCurrentRequest = serverAttemptMadeForCurrentRequest,
                    updateContext = updateContext,
                    requestTag = requestTag,
                ).let(::requireQueued)
            }
            syncScheduleNotifier.scheduleSyncIfNeeded()
            BuoyientLog.d(TAG, "Updated data locally and queued upload (client_id: ${data.clientId})")
            return Pair(data, result)
        } catch (e: QueueWriteException) {
            BuoyientLog.e(TAG, "Failed to queue async update data for client_id: ${data.clientId}")
            return Pair(data, e.queueResult)
        } catch (e: Exception) {
            BuoyientLog.d(TAG, "Failed to update data in db: $e")
            return Pair(data, PendingRequestQueueManager.QueueResult.StoreFailed)
        }
    }

    /**
     * Persist the data back from the server after processing an UPDATE request synchronously with
     * the server while online.
     *
     * @param originalClientId the client_id that originated the request — used as the SQL key
     *   regardless of what [serverData.clientId] contains.
     */
    internal fun upsertFromServerResponse(serverData: O, responseTimestamp: String, originalClientId: String) {
        try {
            val serverDataJson = codec.encodeToString(serverData)
            val encryptedServerDataJson = storageCodec.encodeForStorage(serverDataJson)
            database.syncDataQueries.upsertFromServerResponse(
                last_synced_timestamp = responseTimestamp,
                version = serverData.version,
                sync_status = SyncableObject.SyncStatus.SYNCED,
                data_blob = encryptedServerDataJson,
                last_synced_server_data = encryptedServerDataJson,
                paging_key = serverData.toPagingKey(),
                service_name = serviceName,
                client_id = originalClientId,
            )
            notifyLocalStoreChanged()
        } catch (e: Exception) {
            BuoyientLog.e(TAG, "Failed to upsert data from [update] response (server_id: ${serverData.serverId}): $e")
        }
    }

    internal fun upsertEntry(
        serverObj: O,
        syncedAtTimestamp: String,
        clientId: String,
    ) {
        val serverDataJson = codec.encodeToString(serverObj)
        val encryptedServerDataJson = storageCodec.encodeForStorage(serverDataJson)
        database.syncDataQueries.upsertEntry(
            service_name = serviceName,
            client_id = clientId,
            server_id = serverObj.serverId,
            version = serverObj.version,
            last_synced_timestamp = syncedAtTimestamp,
            data_blob = encryptedServerDataJson,
            sync_status = SyncableObject.SyncStatus.SYNCED,
            last_synced_server_data = encryptedServerDataJson,
            paging_key = serverObj.toPagingKey(),
        )
        notifyLocalStoreChanged()
    }

    internal fun voidData(
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
            BuoyientLog.e(TAG, "Failed to queue void request for client_id: ${data.clientId}")
            return Pair(data, e.queueResult)
        } catch (e: Exception) {
            BuoyientLog.e(TAG, "Failed to store void request: ", e)
            return Pair(data, PendingRequestQueueManager.QueueResult.StoreFailed)
        }
    }

    /**
     * @param originalClientId the client_id that originated the request — used as the SQL key
     *   regardless of what [serverData.clientId] contains.
     */
    internal fun upsertFromVoidServerResponse(serverData: O, responseTimestamp: String, originalClientId: String) {
        try {
            val serverDataJson = codec.encodeToString(serverData)
            val encryptedServerDataJson = storageCodec.encodeForStorage(serverDataJson)
            database.syncDataQueries.upsertFromVoidServerResponse(
                last_synced_timestamp = responseTimestamp,
                version = serverData.version,
                sync_status = SyncableObject.SyncStatus.SYNCED,
                data_blob = encryptedServerDataJson,
                last_synced_server_data = encryptedServerDataJson,
                paging_key = serverData.toPagingKey(),
                service_name = serviceName,
                client_id = originalClientId,
            )
            notifyLocalStoreChanged()
        } catch (e: Exception) {
            BuoyientLog.e(TAG, "Failed to upsert data from [void] response (server_id: ${serverData.serverId}): $e")
        }
    }

    @Throws(Exception::class)
    internal fun voidLocalOnlyData(data: O): O {
        val jsonData = codec.encode(data)
        transaction {
            database.syncDataQueries.voidLocalOnly(
                sync_status = SyncableObject.SyncStatus.LOCAL_ONLY,
                data_blob = storageCodec.encodeForStorage(jsonData.toString()),
                paging_key = data.toPagingKey(),
                service_name = serviceName,
                client_id = data.clientId,
            )
            pendingRequestQueueManager.clearAllPendingRequests(data.clientId)
        }

        BuoyientLog.d(TAG, "Voided local-only object (client_id: ${data.clientId})")
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
        val resolvedDataJson = storageCodec.encodeForStorage(codec.encodeToString(latestServerData))
        when (row.type) {
            PendingSyncRequest.Type.CREATE,
            PendingSyncRequest.Type.UPDATE -> {
                if (rebasedLatestData != null) {
                    database.syncDataQueries.updateAfterCreateOrUpdateUpload(
                        server_id = latestServerData.serverId,
                        last_synced_timestamp = lastSyncedTimestamp,
                        version = latestServerData.version,
                        sync_status = updatedSyncStatus,
                        data_blob = storageCodec.encodeForStorage(codec.encodeToString(rebasedLatestData)),
                        last_synced_server_data = resolvedDataJson,
                        paging_key = rebasedLatestData.toPagingKey(),
                        service_name = serviceName,
                        client_id = row.clientId,
                    )
                } else {
                    database.syncDataQueries.updateAfterCreateOrUpdateUploadWithoutData(
                        server_id = latestServerData.serverId,
                        last_synced_timestamp = lastSyncedTimestamp,
                        version = latestServerData.version,
                        sync_status = updatedSyncStatus,
                        last_synced_server_data = resolvedDataJson,
                        service_name = serviceName,
                        client_id = row.clientId,
                    )
                }
            }

            PendingSyncRequest.Type.VOID -> {
                database.syncDataQueries.updateAfterVoidUpload(
                    last_synced_timestamp = lastSyncedTimestamp,
                    sync_status = updatedSyncStatus,
                    data_blob = resolvedDataJson,
                    last_synced_server_data = resolvedDataJson,
                    version = latestServerData.version,
                    paging_key = latestServerData.toPagingKey(),
                    service_name = serviceName,
                    client_id = row.clientId,
                )
            }
        }
    }

    internal fun getData(clientId: String, serverId: String?): LocalStoreEntry<O>? {
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
            codec.decode(storageCodec.decodeFromStorage(it), SyncableObject.SyncStatus.Synced(lastSyncedTimestamp = lastSyncedTimestamp!!))
        }
        val latestLocalData = codec.decode(storageCodec.decodeFromStorage(row.data_blob), syncStatus)

        return LocalStoreEntry(
            data = latestLocalData,
            latestServerData = lastSyncedServerData,
            lastSyncedTimestamp = lastSyncedTimestamp,
            syncStatus = syncStatus,
        )
    }

    internal fun getAllData(limit: Int): List<LocalStoreEntry<O>> {
        val rows = database.syncDataQueries.getAllData(
            service_name = serviceName,
            limit = limit.toLong(),
        ).executeAsList()
        return rows.map { mapRowToEntry(it.data_blob, it.sync_status, it.last_synced_timestamp, it.client_id, it.last_synced_server_data) }
    }

    /**
     * Returns a [kotlinx.coroutines.flow.Flow] that emits the current list of all [LocalStoreEntry] items for this
     * service whenever the underlying database table changes. Backed by SQLDelight's
     * query-observation mechanism.
     */
    internal fun getAllDataAsFlow(limit: Int): Flow<List<LocalStoreEntry<O>>> {
        return database.syncDataQueries.getAllData(
            service_name = serviceName,
            limit = limit.toLong(),
        ).asFlow().mapToList(ioDispatcher).map { rows ->
            rows.map { mapRowToEntry(it.data_blob, it.sync_status, it.last_synced_timestamp, it.client_id, it.last_synced_server_data) }
        }
    }

    internal fun getFilteredData(
        syncStatus: String?,
        includeVoided: Boolean,
        limit: Int,
    ): List<LocalStoreEntry<O>> {
        val q = database.syncDataQueries
        val l = limit.toLong()
        return when {
            syncStatus != null && !includeVoided ->
                q.getDataBySyncStatusExcludingVoided(serviceName, syncStatus, l).executeAsList()
                    .map { mapRowToEntry(it.data_blob, it.sync_status, it.last_synced_timestamp, it.client_id, it.last_synced_server_data) }
            syncStatus != null ->
                q.getDataBySyncStatus(serviceName, syncStatus, l).executeAsList()
                    .map { mapRowToEntry(it.data_blob, it.sync_status, it.last_synced_timestamp, it.client_id, it.last_synced_server_data) }
            !includeVoided ->
                q.getDataExcludingVoided(serviceName, l).executeAsList()
                    .map { mapRowToEntry(it.data_blob, it.sync_status, it.last_synced_timestamp, it.client_id, it.last_synced_server_data) }
            else ->
                q.getAllData(serviceName, l).executeAsList()
                    .map { mapRowToEntry(it.data_blob, it.sync_status, it.last_synced_timestamp, it.client_id, it.last_synced_server_data) }
        }
    }

    internal fun getFilteredDataAsFlow(
        syncStatus: String?,
        includeVoided: Boolean,
        limit: Int,
    ): Flow<List<LocalStoreEntry<O>>> {
        val q = database.syncDataQueries
        val l = limit.toLong()
        return when {
            syncStatus != null && !includeVoided ->
                q.getDataBySyncStatusExcludingVoided(serviceName, syncStatus, l)
                    .asFlow().mapToList(ioDispatcher).map { rows ->
                        rows.map { mapRowToEntry(it.data_blob, it.sync_status, it.last_synced_timestamp, it.client_id, it.last_synced_server_data) }
                    }
            syncStatus != null ->
                q.getDataBySyncStatus(serviceName, syncStatus, l)
                    .asFlow().mapToList(ioDispatcher).map { rows ->
                        rows.map { mapRowToEntry(it.data_blob, it.sync_status, it.last_synced_timestamp, it.client_id, it.last_synced_server_data) }
                    }
            !includeVoided ->
                q.getDataExcludingVoided(serviceName, l)
                    .asFlow().mapToList(ioDispatcher).map { rows ->
                        rows.map { mapRowToEntry(it.data_blob, it.sync_status, it.last_synced_timestamp, it.client_id, it.last_synced_server_data) }
                    }
            else ->
                q.getAllData(serviceName, l)
                    .asFlow().mapToList(ioDispatcher).map { rows ->
                        rows.map { mapRowToEntry(it.data_blob, it.sync_status, it.last_synced_timestamp, it.client_id, it.last_synced_server_data) }
                    }
        }
    }

    /**
     * Fetches one page of entries from `sync_data` using keyset cursor pagination.
     *
     * Dispatches across 8 generated SQLDelight queries because three independent
     * dimensions each pick a different SQL statement:
     *
     * 1. **First page vs. next page** — first-page queries have no cursor predicate;
     *    next-page queries add a `(paging_key, client_id) > cursor` (or `<` for DESC)
     *    keyset filter. We split into two query families instead of using one query
     *    with a nullable cursor parameter because SQLDelight's parameter-type inference
     *    treats `:cursor IS NULL OR ...` as a non-nullable `String`, which makes a
     *    single unified query uncompilable. Splitting also keeps each statement's
     *    query plan tight (no `OR (cursor IS NULL)` short-circuit).
     *
     * 2. **Sort order** (`ASC` vs `DESC`) — embedded in the query because SQLDelight
     *    can't parameterize `ORDER BY` direction. The cursor comparison flips with the
     *    sort direction (`>` for ASC, `<` for DESC) to keep the resume-after semantics.
     *
     * 3. **Optional `sync_status` filter** — adds an extra `WHERE sync_status = ?`
     *    clause; folded into the query rather than applied in Kotlin so SQLite can use
     *    the existing `(service_name, sync_status, voided)` index.
     *
     * Each branch maps its rows inline because the eight SQLDelight-generated row
     * classes are nominally distinct types — a single `.map { ... }` after the `when`
     * wouldn't type-check.
     */
    internal fun getPage(
        afterCursor: PageCursor?,
        limit: Int,
        syncStatus: String? = null,
    ): List<LocalStoreEntry<O>> {
        val q = database.syncDataQueries
        val l = limit.toLong()
        val descending = pagingConfig.sortOrder == PagingConfig.SortOrder.DESC
        return when {
            // First page: no cursor predicate, ordering + LIMIT alone yield the head.
            afterCursor == null && syncStatus != null && descending ->
                q.getFirstPageBySyncStatusDesc(serviceName, syncStatus, l).executeAsList()
                    .map { mapRowToEntry(it.data_blob, it.sync_status, it.last_synced_timestamp, it.client_id, it.last_synced_server_data) }
            afterCursor == null && syncStatus != null ->
                q.getFirstPageBySyncStatusAsc(serviceName, syncStatus, l).executeAsList()
                    .map { mapRowToEntry(it.data_blob, it.sync_status, it.last_synced_timestamp, it.client_id, it.last_synced_server_data) }
            afterCursor == null && descending ->
                q.getFirstPageDesc(serviceName, l).executeAsList()
                    .map { mapRowToEntry(it.data_blob, it.sync_status, it.last_synced_timestamp, it.client_id, it.last_synced_server_data) }
            afterCursor == null ->
                q.getFirstPageAsc(serviceName, l).executeAsList()
                    .map { mapRowToEntry(it.data_blob, it.sync_status, it.last_synced_timestamp, it.client_id, it.last_synced_server_data) }
            // Next page: composite (paging_key, client_id) cursor — the client_id
            // tiebreak prevents skipping rows that share a paging_key with the cursor row.
            syncStatus != null && descending ->
                q.getNextPageBySyncStatusDesc(serviceName, syncStatus, afterCursor.key, afterCursor.clientId, l).executeAsList()
                    .map { mapRowToEntry(it.data_blob, it.sync_status, it.last_synced_timestamp, it.client_id, it.last_synced_server_data) }
            syncStatus != null ->
                q.getNextPageBySyncStatusAsc(serviceName, syncStatus, afterCursor.key, afterCursor.clientId, l).executeAsList()
                    .map { mapRowToEntry(it.data_blob, it.sync_status, it.last_synced_timestamp, it.client_id, it.last_synced_server_data) }
            descending ->
                q.getNextPageDesc(serviceName, afterCursor.key, afterCursor.clientId, l).executeAsList()
                    .map { mapRowToEntry(it.data_blob, it.sync_status, it.last_synced_timestamp, it.client_id, it.last_synced_server_data) }
            else ->
                q.getNextPageAsc(serviceName, afterCursor.key, afterCursor.clientId, l).executeAsList()
                    .map { mapRowToEntry(it.data_blob, it.sync_status, it.last_synced_timestamp, it.client_id, it.last_synced_server_data) }
        }
    }

    /**
     * Lazily creates SQLite expression indexes for each path in [indexedJsonPaths]
     * the first time a filter query runs. `CREATE INDEX IF NOT EXISTS` keeps this
     * idempotent across LocalStoreManager re-instantiation within the same DB.
     *
     * Index name is derived from `(serviceName, path)` so two services indexing
     * the same JSON path produce distinct indexes — both are scoped on
     * `service_name` in the WHERE clause anyway, but separate indexes give the
     * planner the cleanest options.
     */
    private var indexesCreated = false
    private fun ensureIndexedPaths() {
        if (indexesCreated) return
        val drv = driver ?: return
        if (indexedJsonPaths.isEmpty()) {
            indexesCreated = true
            return
        }
        for (path in indexedJsonPaths) {
            require(JSON_PATH_REGEX.matches(path)) {
                "Invalid JSON path '$path' — must match $.field, $.nested.field, or $.array[0]"
            }
            val indexName = "idx_${serviceName.toIndexSlug()}_${path.toIndexSlug()}"
            val sql = "CREATE INDEX IF NOT EXISTS $indexName " +
                "ON sync_data(service_name, json_extract(data_blob, '$path'))"
            drv.execute(identifier = null, sql = sql, parameters = 0, binders = null)
        }
        indexesCreated = true
    }

    /**
     * Filter-aware variant of [getPage]. Drops to dynamic SQL because the filter
     * tree's shape varies per call; the static SQLDelight queries can only express
     * the no-filter combinations. The cursor predicate, sort direction, optional
     * `sync_status`, and `voided = 0` clauses are all assembled here in the same
     * shape as the static queries so semantics stay aligned.
     *
     * Requires [driver] to be non-null. Throws [IllegalStateException] otherwise.
     */
    internal fun getPageWithFilter(
        afterCursor: PageCursor?,
        limit: Int,
        syncStatus: String?,
        filter: Filter,
    ): List<LocalStoreEntry<O>> {
        val drv = checkNotNull(driver) {
            "Filter queries require a driver. Set DatabaseOverride.driver (or use Buoyient.databaseHandle)."
        }
        ensureIndexedPaths()

        val descending = pagingConfig.sortOrder == PagingConfig.SortOrder.DESC
        val whereClauses = mutableListOf("service_name = ?", "voided = 0")
        val params = mutableListOf<Any?>(serviceName)

        if (syncStatus != null) {
            whereClauses += "sync_status = ?"
            params += syncStatus
        }

        val (filterClause, filterParams) = filter.toSql()
        whereClauses += filterClause
        params.addAll(filterParams)

        if (afterCursor != null) {
            val cmp = if (descending) "<" else ">"
            whereClauses += "(paging_key $cmp ? OR (paging_key = ? AND client_id $cmp ?))"
            params.add(afterCursor.key)
            params.add(afterCursor.key)
            params.add(afterCursor.clientId)
        }

        val dir = if (descending) "DESC" else "ASC"
        val sql = buildString {
            append("SELECT data_blob, sync_status, last_synced_timestamp, client_id, last_synced_server_data\n")
            append("FROM sync_data\n")
            append("WHERE ").append(whereClauses.joinToString(" AND ")).append('\n')
            append("ORDER BY paging_key ").append(dir).append(", client_id ").append(dir).append('\n')
            append("LIMIT ?")
        }
        params.add(limit.toLong())

        return drv.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                val rows = mutableListOf<LocalStoreEntry<O>>()
                while (cursor.next().value) {
                    rows += mapRowToEntry(
                        dataBlob = cursor.getString(0)!!,
                        syncStatusValue = cursor.getString(1)!!,
                        lastSyncedTimestamp = cursor.getString(2),
                        clientId = cursor.getString(3)!!,
                        lastSyncedServerData = cursor.getString(4),
                    )
                }
                QueryResult.Value(rows.toList())
            },
            parameters = params.size,
            binders = {
                params.forEachIndexed { index, value -> bindAny(index, value) }
            },
        ).value
    }

    private fun mapRowToEntry(
        dataBlob: String,
        syncStatusValue: String,
        lastSyncedTimestamp: String?,
        clientId: String,
        lastSyncedServerData: String?,
    ): LocalStoreEntry<O> {
        val latestSyncStatus = SyncableObject.SyncStatus.buildFromDbContext(
            status = syncStatusValue,
            lastSyncedTimestamp = lastSyncedTimestamp,
            conflictInfo = if (syncStatusValue == SyncableObject.SyncStatus.CONFLICT) {
                pendingRequestQueueManager.getConflicts(clientId = clientId).toFieldConflictInfo()
            } else {
                emptyList()
            },
        )
        val data = codec.decode(storageCodec.decodeFromStorage(dataBlob), latestSyncStatus)
        val latestServerData = lastSyncedServerData?.let {
            codec.decode(storageCodec.decodeFromStorage(it), SyncableObject.SyncStatus.Synced(lastSyncedTimestamp!!))
        }
        return LocalStoreEntry(
            data = data,
            latestServerData = latestServerData,
            lastSyncedTimestamp = lastSyncedTimestamp,
            syncStatus = latestSyncStatus,
        )
    }

    /**
     * When we sync down from the server and receive updates to a data object we are tracking
     * locally, we need to upsert that new server data into the local store and rebase any
     * potential pending upload requests.
     */
    internal fun upsertSyncDownResponseData(
        clientId: String,
        lastSyncedTimestamp: String,
        updatedServerData: O,
        mergeHandler: SyncableObjectRebaseHandler<O>,
    ): UpsertResult {
        val result = database.transactionWithResult {
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
                    version = updatedServerData.version,
                    sync_status = SyncableObject.SyncStatus.SYNCED,
                    data_blob = storageCodec.encodeForStorage(codec.encodeToString(rebasedLocalData)),
                    last_synced_server_data = storageCodec.encodeForStorage(codec.encodeToString(updatedServerData)),
                    paging_key = rebasedLocalData.toPagingKey(),
                    service_name = serviceName,
                    client_id = clientId,
                )
            }
        }
        notifyLocalStoreChanged()
        return result
    }

    /**
     * When we sync pending requests up to the server & get a response back from the server,
     * the server data returned in the response from that sync up needs to be upserted into our
     * local data store and all pending sync requests need to be rebased on that updated server
     * data. This function handles that.
     */
    internal fun upsertPendingRequestSyncResponseData(
        updatedServerData: O,
        lastSyncedTimestamp: String,
        syncedPendingRequest: PendingSyncRequest<O>,
        mergeHandler: SyncableObjectRebaseHandler<O>,
    ) {
        transaction {
            when (
                val clearResult = pendingRequestQueueManager.clearPendingRequestAfterUpload(
                    pendingRequestId = syncedPendingRequest.pendingRequestId,
                    clientId = syncedPendingRequest.clientId,
                )
            ) {
                is PendingRequestQueueManager.ClearRequestResult.FailedToRemoveEntry ->
                    throw IllegalStateException("Failed to remove pending sync entry, try again after it is idempotently retried.")

                is PendingRequestQueueManager.ClearRequestResult.Cleared -> {
                    rebaseData(
                        clientId = syncedPendingRequest.clientId,
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
                    server_id = updatedServerData.serverId,
                    last_synced_server_data = storageCodec.encodeForStorage(codec.encodeToString(updatedServerData)),
                    last_synced_timestamp = lastSyncedTimestamp,
                    service_name = serviceName,
                    client_id = clientId,
                )
                return UpsertResult.ConflictFailure
            }
        }
    }

    internal fun updateLocalDataAfterPendingRequestSync(
        processedLocalData: O,
        lastSyncedTimestamp: String,
        syncedPendingRequest: PendingSyncRequest<O>,
    ) {
        transaction {
            when (
                val clearResult = pendingRequestQueueManager.clearPendingRequestAfterUpload(
                    pendingRequestId = syncedPendingRequest.pendingRequestId,
                    clientId = syncedPendingRequest.clientId,
                )
            ) {
                is PendingRequestQueueManager.ClearRequestResult.FailedToRemoveEntry -> {
                    val errorMessage = "Failed to remove pending sync entry, try again after it is idempotently retried."
                    BuoyientLog.e(TAG, errorMessage)
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
    internal fun resolveConflictData(
        resolvedData: O,
        resolvedHttpRequest: HttpRequest,
        mergeHandler: SyncableObjectRebaseHandler<O>,
    ): ResolveConflictResult<O> {
        val clientId = resolvedData.clientId
        val entry = getData(clientId = clientId, serverId = resolvedData.serverId)
            ?: return ResolveConflictResult.Failed(IllegalStateException("No sync_data entry found for client_id: $clientId"))

        if (entry.syncStatus !is SyncableObject.SyncStatus.Conflict) {
            BuoyientLog.w(TAG, "sync_data entry for client_id: $clientId is not in CONFLICT status (current: ${entry.syncStatus})")
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
                            data_blob = storageCodec.encodeForStorage(codec.encodeToString(latestData)),
                            server_id = latestData.serverId ?: newServerBaseline.serverId,
                            paging_key = latestData.toPagingKey(),
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
            notifyLocalStoreChanged()
            result
        } catch (e: Exception) {
            BuoyientLog.e(TAG, "Failed to resolve conflict for client_id: $clientId", e)
            ResolveConflictResult.Failed(e)
        }
    }

    /**
     * Self-heals a sync_data row that is stuck in CONFLICT status when no pending request
     * actually has conflict_info. This is a defensive repair for an inconsistent state that
     * should not normally occur.
     *
     * If pending requests exist, rebases them against each other using the first request's
     * baseData as the base, then updates sync_data status based on the result.
     * If no pending requests exist, transitions sync_data back to SYNCED.
     *
     * @return [ResolveConflictResult.Resolved] with the current latest data on success,
     *  [ResolveConflictResult.RebaseConflict] if rebasing reveals a real conflict, or
     *  [ResolveConflictResult.Failed] on error.
     */
    internal fun repairOrphanedConflictStatus(
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
                        data_blob = storageCodec.encodeForStorage(codec.encodeToString(entry.data)),
                        server_id = serverId ?: entry.data.serverId,
                        paging_key = entry.data.toPagingKey(),
                        service_name = serviceName,
                        client_id = clientId,
                    )
                    return@transactionWithResult ResolveConflictResult.Resolved(resolvedData = entry.data)
                }

                // Use the first pending request's baseData as the base for rebasing.
                val baseData = pendingRequests.first().baseData ?: entry.latestServerData
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
                        data_blob = storageCodec.encodeForStorage(codec.encodeToString(latestData)),
                        server_id = serverId ?: latestData.serverId,
                        paging_key = latestData.toPagingKey(),
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
                            data_blob = storageCodec.encodeForStorage(codec.encodeToString(entry.data)),
                            server_id = serverId ?: entry.data.serverId,
                            paging_key = entry.data.toPagingKey(),
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
                            data_blob = storageCodec.encodeForStorage(codec.encodeToString(latestData)),
                            server_id = serverId ?: latestData.serverId,
                            paging_key = latestData.toPagingKey(),
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
            notifyLocalStoreChanged()
            result
        } catch (e: Exception) {
            BuoyientLog.e(TAG, "Failed to repair orphaned conflict for client_id: $clientId", e)
            ResolveConflictResult.Failed(e)
        }
    }

    /**
     * Looks up the canonical [client_id] for a given [serverId]. Returns null if no row
     * exists for that server_id, meaning this is a genuinely new item from the server.
     */
    internal fun getExistingClientId(serverId: String?): String? {
        if (serverId == null) return null
        return database.syncDataQueries.getClientIdByServerId(
            service_name = serviceName,
            server_id = serverId,
        ).executeAsOneOrNull()
    }

    internal class LocalStoreEntry<O>(
        internal val data: O,
        internal val latestServerData: O?,
        internal val lastSyncedTimestamp: String?,
        internal val syncStatus: SyncableObject.SyncStatus,
    )

    internal companion object {
        internal const val TAG: String = "SyncableObjectService:LocalStoreManager"

        /**
         * Permits `$.field`, `$.nested.field`, and array subscripts `$.list[0]`.
         * Rejects anything that could break embedding the path in `CREATE INDEX`
         * SQL (single quotes, backslashes, semicolons, etc.).
         */
        private val JSON_PATH_REGEX = Regex("""^\$(\.[A-Za-z_][A-Za-z0-9_]*(\[\d+\])?)+$""")
    }
}

/** Slugify for use as a SQLite identifier (index name component). */
internal fun String.toIndexSlug(): String =
    removePrefix("$.").replace(Regex("[^A-Za-z0-9]"), "_")

/**
 * Binds an arbitrary Kotlin value to a prepared-statement parameter using a type-
 * appropriate driver method. Numbers go through `bindLong` / `bindDouble` so
 * SQLite compares them numerically against `json_extract` values; strings go
 * through `bindString`. Everything else is rendered via `toString()` as a fallback.
 */
internal fun SqlPreparedStatement.bindAny(index: Int, value: Any?) {
    when (value) {
        null -> bindString(index, null)
        is String -> bindString(index, value)
        is Boolean -> bindBoolean(index, value)
        is Int -> bindLong(index, value.toLong())
        is Long -> bindLong(index, value)
        is Float -> bindDouble(index, value.toDouble())
        is Double -> bindDouble(index, value)
        is ByteArray -> bindBytes(index, value)
        else -> bindString(index, value.toString())
    }
}