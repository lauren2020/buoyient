package com.les.databuoy

import com.les.databuoy.db.SyncDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class PendingRequestQueueManager<O : SyncableObject<O>, T : ServiceRequestTag>(
    internal val database: SyncDatabase,
    internal val serviceName: String,
    internal val strategy: PendingRequestQueueStrategy,
    internal val codec: SyncCodec<O>,
    private val status: DataBuoyStatus = DataBuoyStatus(database),
) {
    sealed class PendingRequestQueueStrategy {
        class Squash(
            val squashUpdateIntoCreate: SquashRequestMerger,
        ) : PendingRequestQueueStrategy()

        object Queue : PendingRequestQueueStrategy()
    }

    sealed class QueueResult {
        object Stored : QueueResult()

        class InvalidQueueRequest(val errorMessage: String) : QueueResult()

        object StoreFailed : QueueResult()
    }

    /**
     * Describes how an update request should be queued.
     *
     * [Store] — the request has not been attempted on the server. The queue may squash it with
     * other pending requests using the provided [UpdateRequestBuilder] when a
     * [PendingRequestQueueStrategy.Squash] strategy is configured.
     *
     * [StoreAfterServerAttempt] — the request was already sent to the server (e.g. timed out).
     * The queue must store the exact [HttpRequest] as-is and never rebuild or squash it, since the
     * server may have already processed the original request body.
     */
    sealed class UpdateQueueInstruction<O : SyncableObject<O>> {
        class Store<O : SyncableObject<O>>(
            val httpRequest: HttpRequest,
            val buildRequest: UpdateRequestBuilder<O>,
        ) : UpdateQueueInstruction<O>()

        class StoreAfterServerAttempt<O : SyncableObject<O>>(
            val httpRequest: HttpRequest,
        ) : UpdateQueueInstruction<O>()
    }

    fun queueCreateRequest(
        data: O,
        httpRequest: HttpRequest,
        idempotencyKey: String,
        serverAttemptMade: Boolean,
        requestTag: T,
    ): QueueResult {
        val pendingSyncRequest = PendingSyncRequest(
            type = PendingSyncRequest.Type.CREATE,
            idempotencyKey = idempotencyKey,
            request = httpRequest,
            serverAttemptMade = serverAttemptMade,
            data = data,
            lastSyncedData = null,
            requestTag = requestTag.value,
        )
        val pendingSyncRequests = getPendingRequests(data.clientId)
        if (
            pendingSyncRequests.any { it.type == PendingSyncRequest.Type.CREATE }
        ) {
            return QueueResult.InvalidQueueRequest("There is already a create request queued for client id: ${data.clientId}")
        }
        if (
            pendingSyncRequests.any { it.type == PendingSyncRequest.Type.VOID }
        ) {
            return QueueResult.InvalidQueueRequest("This object (client_id: ${data.clientId}) has already been voided.")
        }
        return storeEntry(pendingSyncRequest)
    }

    fun queueUpdateRequest(
        data: O,
        idempotencyKey: String,
        lastSyncedData: O?,
        instruction: UpdateQueueInstruction<O>,
        requestTag: T,
    ): QueueResult {
        val httpRequest = when (instruction) {
            is UpdateQueueInstruction.Store -> instruction.httpRequest
            is UpdateQueueInstruction.StoreAfterServerAttempt -> instruction.httpRequest
        }
        val serverAttemptMade = instruction is UpdateQueueInstruction.StoreAfterServerAttempt

        return when (strategy) {
            is PendingRequestQueueStrategy.Squash -> {
                when (instruction) {
                    // Request was already attempted on the server — store the exact request
                    // as-is without any squashing. The server may have already processed
                    // the original request body.
                    is UpdateQueueInstruction.StoreAfterServerAttempt -> {
                        if (getPendingRequests(data.clientId).any { it.type == PendingSyncRequest.Type.VOID }) {
                            QueueResult.InvalidQueueRequest("Cannot make updates after void!")
                        } else {
                            storeEntry(
                                pendingSyncRequest = PendingSyncRequest(
                                    type = PendingSyncRequest.Type.UPDATE,
                                    idempotencyKey = idempotencyKey,
                                    request = httpRequest,
                                    serverAttemptMade = true,
                                    data = data,
                                    lastSyncedData = lastSyncedData,
                                    requestTag = requestTag.value,
                                ),
                            )
                        }
                    }

                    is UpdateQueueInstruction.Store -> {
                        val pendingRequests = getPendingRequests(data.clientId)
                        val latestPendingRequest = pendingRequests.lastOrNull()
                        when (latestPendingRequest?.type) {
                            PendingSyncRequest.Type.VOID -> QueueResult.InvalidQueueRequest("Cannot make updates after void!")

                            PendingSyncRequest.Type.CREATE -> {
                                if (latestPendingRequest.serverAttemptMade) {
                                    storeEntry(
                                        pendingSyncRequest = PendingSyncRequest(
                                            type = PendingSyncRequest.Type.UPDATE,
                                            idempotencyKey = idempotencyKey,
                                            request = httpRequest,
                                            serverAttemptMade = true,
                                            data = data,
                                            lastSyncedData = lastSyncedData,
                                            requestTag = requestTag.value,
                                        ),
                                    )
                                } else {
                                    val squashedCreateRequest = strategy.squashUpdateIntoCreate.merge(
                                        latestPendingRequest.request,
                                        httpRequest,
                                    )
                                    storeEntry(
                                        pendingSyncRequest = PendingSyncRequest(
                                            pendingRequestId = -1,
                                            type = PendingSyncRequest.Type.CREATE,
                                            idempotencyKey = idempotencyKey,
                                            request = squashedCreateRequest,
                                            serverAttemptMade = false,
                                            data = data,
                                            lastSyncedData = lastSyncedData,
                                            requestTag = requestTag.value,
                                        ),
                                    )
                                }
                            }

                            PendingSyncRequest.Type.UPDATE -> {
                                if (latestPendingRequest.serverAttemptMade) {
                                    storeEntry(
                                        pendingSyncRequest = PendingSyncRequest(
                                            type = PendingSyncRequest.Type.UPDATE,
                                            idempotencyKey = idempotencyKey,
                                            request = httpRequest,
                                            serverAttemptMade = false,
                                            data = data,
                                            lastSyncedData = lastSyncedData,
                                            requestTag = requestTag.value,
                                        ),
                                    )
                                } else {
                                    val squashedUpdateRequest = instruction.buildRequest.buildRequest(
                                        lastSyncedData = latestPendingRequest.data,
                                        updatedData = data,
                                        idempotencyKey = idempotencyKey,
                                        isAsync = true,
                                        attemptedServerRequest = null,
                                    )
                                    replaceEntry(
                                        latestPendingRequest.copy(
                                            request = squashedUpdateRequest,
                                            requestTag = requestTag.value,
                                        )
                                    )
                                }
                            }

                            null -> {
                                storeEntry(
                                    pendingSyncRequest = PendingSyncRequest(
                                        type = PendingSyncRequest.Type.UPDATE,
                                        idempotencyKey = idempotencyKey,
                                        request = httpRequest,
                                        serverAttemptMade = false,
                                        data = data,
                                        lastSyncedData = lastSyncedData,
                                        requestTag = requestTag.value,
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            is PendingRequestQueueStrategy.Queue -> {
                if (getPendingRequests(data.clientId).any { it.type == PendingSyncRequest.Type.VOID }) {
                    QueueResult.InvalidQueueRequest("Cannot make updates after void!")
                } else {
                    storeEntry(
                        pendingSyncRequest = PendingSyncRequest(
                            type = PendingSyncRequest.Type.UPDATE,
                            idempotencyKey = idempotencyKey,
                            request = httpRequest,
                            serverAttemptMade = serverAttemptMade,
                            data = data,
                            lastSyncedData = lastSyncedData,
                            requestTag = requestTag.value,
                        ),
                    )
                }
            }
        }
    }

    fun queueVoidRequest(
        data: O,
        httpRequest: HttpRequest,
        idempotencyKey: String,
        serverAttemptMade: Boolean,
        lastSyncedServerData: O?,
        requestTag: T,
    ): QueueResult {
        return storeEntry(
            PendingSyncRequest(
                type = PendingSyncRequest.Type.VOID,
                idempotencyKey = idempotencyKey,
                request = httpRequest,
                serverAttemptMade = serverAttemptMade,
                data = data,
                lastSyncedData = lastSyncedServerData,
                requestTag = requestTag.value,
            )
        )
    }

    private fun storeEntry(
        pendingSyncRequest: PendingSyncRequest<O>,
    ): QueueResult = try {
        database.syncPendingEventsQueries.insertPendingEvent(
            service_name = serviceName,
            client_id = pendingSyncRequest.data.clientId,
            data_blob = codec.encodeToString(pendingSyncRequest.data),
            type = pendingSyncRequest.type.value,
            request = pendingSyncRequest.request.toJson().toString(),
            idempotency_key = pendingSyncRequest.idempotencyKey,
            server_attempt_made = if (pendingSyncRequest.serverAttemptMade) 1L else 0L,
            conflict_info = null,
            last_synced_data = pendingSyncRequest.lastSyncedData?.let { codec.encodeToString(it) },
            request_tag = pendingSyncRequest.requestTag,
        )
        status.refresh()
        QueueResult.Stored
    } catch (e: Exception) {
        QueueResult.StoreFailed
    }

    private fun replaceEntry(
        pendingSyncRequest: PendingSyncRequest<O>,
    ): QueueResult = try {
        database.syncPendingEventsQueries.replaceEntry(
            client_id = pendingSyncRequest.data.clientId,
            data_blob = codec.encodeToString(pendingSyncRequest.data),
            type = PendingSyncRequest.Type.UPDATE.value,
            request = pendingSyncRequest.request.toJson().toString(),
            idempotency_key = pendingSyncRequest.idempotencyKey,
            server_attempt_made = if (pendingSyncRequest.serverAttemptMade) 1L else 0L,
            conflict_info = pendingSyncRequest.conflict?.toJson(codec)?.toString(),
            last_synced_data = pendingSyncRequest.lastSyncedData?.let { codec.encodeToString(it) },
            request_tag = pendingSyncRequest.requestTag,
            pending_request_id = pendingSyncRequest.pendingRequestId.toLong(),
        )
        status.refresh()
        QueueResult.Stored
    } catch (e: Exception) {
        QueueResult.StoreFailed
    }

    fun hasPendingRequests(clientId: String): Boolean =
        database.syncPendingEventsQueries.hasPendingRequestsByClientId(
            service_name = serviceName,
            client_id = clientId,
        ).executeAsOne()

    fun getLatestPendingRequest(clientId: String): PendingSyncRequest<O>? =
        getPendingRequests(clientId).lastOrNull()

    fun getPendingRequestById(pendingRequestId: Int): PendingSyncRequest<O>? {
        val row = database.syncPendingEventsQueries.getPendingRequestById(
            pending_request_id = pendingRequestId.toLong(),
        ).executeAsOneOrNull() ?: return null
        return mapRowToPendingSyncRequest(
            row.pending_request_id, row.type, row.idempotency_key,
            row.request, row.server_attempt_made, row.data_blob, row.conflict_info,
            row.last_synced_data, row.request_tag,
        )
    }

    fun getPendingRequests(clientId: String): List<PendingSyncRequest<O>> {
        return database.syncPendingEventsQueries.getPendingRequestsByClientId(
            service_name = serviceName,
            client_id = clientId,
        ).executeAsList().map { row ->
            mapRowToPendingSyncRequest(row.pending_request_id, row.type, row.idempotency_key,
                row.request, row.server_attempt_made, row.data_blob, row.conflict_info, row.last_synced_data,
                row.request_tag)
        }
    }

    fun getPendingRequests(): List<PendingSyncRequest<O>> {
        return database.syncPendingEventsQueries.getAllPendingRequests(
            service_name = serviceName,
        ).executeAsList().map { row ->
            mapRowToPendingSyncRequest(row.pending_request_id, row.type, row.idempotency_key,
                row.request, row.server_attempt_made, row.data_blob, row.conflict_info, row.last_synced_data,
                row.request_tag)
        }
    }

    private fun mapRowToPendingSyncRequest(
        pendingRequestId: Long,
        type: String,
        idempotencyKey: String,
        request: String,
        serverAttemptMade: Long,
        data: String,
        conflictInfo: String?,
        lastSyncedData: String?,
        requestTag: String,
    ): PendingSyncRequest<O> = PendingSyncRequest(
        pendingRequestId = pendingRequestId.toInt(),
        type = PendingSyncRequest.Type.fromValue(type),
        idempotencyKey = idempotencyKey,
        request = HttpRequest.fromJson(Json.parseToJsonElement(request).jsonObject),
        serverAttemptMade = serverAttemptMade != 0L,
        data = codec.decode(data, SyncableObject.SyncStatus.LocalOnly),
        conflict = conflictInfo?.let {
            SyncableObjectRebaseHandler.FieldConflict.fromJson(
                jsonObject = Json.parseToJsonElement(it).jsonObject,
                codec = codec,
            )
        },
        lastSyncedData = lastSyncedData?.let {
            codec.decode(it, SyncableObject.SyncStatus.Synced(""))
        },
        requestTag = requestTag,
    )

    fun clearAllPendingRequests(
        clientId: String,
    ) {
        database.syncPendingEventsQueries.clearAllByClientId(
            service_name = serviceName,
            client_id = clientId,
        )
        status.refresh()
    }

    fun clearPendingRequestAfterUpload(
        pendingRequestId: Int,
        clientId: String,
    ): ClearRequestResult = try {
        database.syncPendingEventsQueries.clearById(
            pending_request_id = pendingRequestId.toLong(),
        )
        val nextPendingSyncRequest = getPendingRequests(clientId = clientId).firstOrNull()
        val updatedSyncStatus = when (nextPendingSyncRequest?.type) {
            PendingSyncRequest.Type.CREATE -> SyncableObject.SyncStatus.PENDING_CREATE
            PendingSyncRequest.Type.UPDATE -> SyncableObject.SyncStatus.PENDING_UPDATE
            PendingSyncRequest.Type.VOID -> SyncableObject.SyncStatus.PENDING_VOID
            null -> SyncableObject.SyncStatus.SYNCED
        }
        status.refresh()
        ClearRequestResult.Cleared(updatedSyncStatus = updatedSyncStatus)
    } catch (e: Exception) {
        ClearRequestResult.FailedToRemoveEntry
    }

    sealed class ClearRequestResult {
        class Cleared(val updatedSyncStatus: String) : ClearRequestResult()

        object FailedToRemoveEntry : ClearRequestResult()
    }

    fun markPendingRequestAsAttempted(pendingRequestId: Int) {
        database.syncPendingEventsQueries.markAsAttempted(
            pending_request_id = pendingRequestId.toLong(),
        )
    }

    fun rebaseDataForRemainingPendingRequests(
        clientId: String,
        updatedBaseData: O,
        mergeHandler: SyncableObjectRebaseHandler<O>,
    ): RebasePendingRequestsResult<O> {
        val pendingRequests = getPendingRequests(clientId = clientId)
        if (pendingRequests.isEmpty()) return RebasePendingRequestsResult.NoPendingRequestRemaining()
        var nextBase = updatedBaseData
        pendingRequests.forEach {
            val rebaseMergeResult = rebasePendingSync(
                pendingSyncRequest = it,
                newBaseData = nextBase,
                mergeHandler = mergeHandler,
            )
            when (rebaseMergeResult) {
                is SyncableObjectRebaseHandler.RebaseResult.Rebased -> {
                    nextBase = rebaseMergeResult.mergedData
                }
                is SyncableObjectRebaseHandler.RebaseResult.Conflict -> {
                    SyncLog.w(TAG, "PendingSyncRequest (pending_request_id: ${it.pendingRequestId}) encountered a conflict on rebase, aborting rebase of subsequent pending requests until resolved.")
                    // Exit the loop early and abort attempting any further rebases until the
                    // current conflict is resolved.
                    return RebasePendingRequestsResult.AbortedRebaseToConflicts(
                        conflict = rebaseMergeResult.conflict,
                    )
                }
            }
        }
        status.refresh()
        return RebasePendingRequestsResult.RebasedRemainingPendingRequests(
            rebasedLatestData = nextBase,
        )
    }

    sealed class RebasePendingRequestsResult<O : SyncableObject<O>> {
        class NoPendingRequestRemaining<O : SyncableObject<O>> : RebasePendingRequestsResult<O>()

        class RebasedRemainingPendingRequests<O : SyncableObject<O>>(
            val rebasedLatestData: O,
        ) : RebasePendingRequestsResult<O>()

        class AbortedRebaseToConflicts<O : SyncableObject<O>>(
            val conflict: SyncableObjectRebaseHandler.FieldConflict<O>,
        ) : RebasePendingRequestsResult<O>()
    }

    /**
     * Returns the new latest data for the row
     */
    private fun rebasePendingSync(
        pendingSyncRequest: PendingSyncRequest<O>,
        newBaseData: O,
        mergeHandler: SyncableObjectRebaseHandler<O>,
    ): SyncableObjectRebaseHandler.RebaseResult<O> {
        val mergeResult = mergeHandler.rebaseDataForPendingRequest(
            oldBaseData = pendingSyncRequest.lastSyncedData,
            currentData = pendingSyncRequest.data,
            newBaseData = newBaseData,
            pendingHttpRequest = pendingSyncRequest.request,
            pendingRequestId = pendingSyncRequest.pendingRequestId,
            requestTag = pendingSyncRequest.requestTag,
        )
        when (mergeResult) {
            is SyncableObjectRebaseHandler.RebaseResult.Rebased -> {
                storeRebasedPendingRequest(
                    rebasedData = mergeResult.mergedData,
                    newBaseData = newBaseData,
                    updatedRequest = mergeResult.updatedHttpRequest,
                    pendingRequestId = pendingSyncRequest.pendingRequestId,
                )
            }

            is SyncableObjectRebaseHandler.RebaseResult.Conflict -> {
                handlePendingRequestRebaseConflict(
                    pendingSyncRequest = pendingSyncRequest,
                    newBaseData = newBaseData,
                    conflict = mergeResult,
                    mergeHandler = mergeHandler,
                )
            }
        }
        return mergeResult
    }

    private fun storeRebasedPendingRequest(
        rebasedData: O,
        newBaseData: O,
        updatedRequest: HttpRequest?,
        pendingRequestId: Int,
    ) {
        if (updatedRequest != null) {
            database.syncPendingEventsQueries.rebasePendingSyncWithRequest(
                // TODO: Should we rename last_synced_data to base_data
                last_synced_data = codec.encodeToString(newBaseData),
                data_blob = codec.encodeToString(rebasedData),
                request = updatedRequest.toJson().toString(),
                pending_request_id = pendingRequestId.toLong(),
            )
        } else {
            database.syncPendingEventsQueries.rebasePendingSync(
                last_synced_data = codec.encodeToString(newBaseData),
                data_blob = codec.encodeToString(rebasedData),
                pending_request_id = pendingRequestId.toLong(),
            )
        }
        status.refresh()
    }

    private fun handlePendingRequestRebaseConflict(
        pendingSyncRequest: PendingSyncRequest<O>,
        newBaseData: O,
        conflict: SyncableObjectRebaseHandler.RebaseResult.Conflict<O>,
        mergeHandler: SyncableObjectRebaseHandler<O>,
    ) = when (
        val resolution = mergeHandler.handleMergeConflict(conflict, requestTag = pendingSyncRequest.requestTag)
    ) {
        is SyncableObjectRebaseHandler.ConflictResolution.Resolved -> {
            storeRebasedPendingRequest(
                rebasedData = resolution.resolvedData,
                newBaseData = newBaseData,
                updatedRequest = resolution.updatedHttpRequest,
                pendingRequestId = pendingSyncRequest.pendingRequestId,
            )
        }

        is SyncableObjectRebaseHandler.ConflictResolution.Unresolved -> {
            database.syncPendingEventsQueries.saveConflictInfo(
                conflict_info = conflict.conflict.toJson(codec).toString(),
                pending_request_id = pendingSyncRequest.pendingRequestId.toLong(),
            )
            status.refresh()
        }
    }

    sealed class UpsertPendingChangesResult {
        object NoPendingRequests : UpsertPendingChangesResult()

        class MergedAllPendingChanges(val resolvedConflicts: Boolean) : UpsertPendingChangesResult()

        object PendingChangesConflict : UpsertPendingChangesResult()
    }

    /**
     * Returns the first pending request for [clientId] that has a non-null conflict,
     * or null if no conflicting request exists.
     */
    fun getConflictingPendingRequest(clientId: String): PendingSyncRequest<O>? =
        getPendingRequests(clientId).firstOrNull { it.conflict != null }

    /**
     * Resolves a conflict on a pending request by replacing its data and request with
     * the consumer-provided resolved values, clearing the conflict_info, and updating
     * the last_synced_data to the new server baseline.
     */
    fun resolveConflictOnPendingRequest(
        pendingRequest: PendingSyncRequest<O>,
        resolvedData: O,
        resolvedHttpRequest: HttpRequest,
        newServerBaseline: O,
    ) {
        database.syncPendingEventsQueries.resolveConflict(
            data_blob = codec.encodeToString(resolvedData),
            request = resolvedHttpRequest.toJson().toString(),
            last_synced_data = codec.encodeToString(newServerBaseline),
            pending_request_id = pendingRequest.pendingRequestId.toLong(),
        )
        status.refresh()
    }

    fun hasAnyConflictsGlobally(): Boolean =
        database.syncPendingEventsQueries.hasAnyConflicts().executeAsOne()

    fun getConflicts(clientId: String): List<SyncableObjectRebaseHandler.FieldConflict<O>> {
        return getPendingRequests(clientId).mapNotNull { pendingSyncRequest ->
            pendingSyncRequest.conflict
        }
    }

    companion object {
        const val TAG = "SyncableObjectService:PendingRequestQueueManager"
    }
}
