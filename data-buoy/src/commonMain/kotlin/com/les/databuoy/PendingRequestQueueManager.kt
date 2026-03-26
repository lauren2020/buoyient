package com.les.databuoy

import com.les.databuoy.db.SyncDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

public class PendingRequestQueueManager<O : SyncableObject<O>, T : ServiceRequestTag>(
    internal val database: SyncDatabase,
    internal val serviceName: String,
    internal val strategy: PendingRequestQueueStrategy,
    internal val codec: SyncCodec<O>,
    private val status: DataBuoyStatus = DataBuoyStatus(database),
    internal val storageCodec: StorageCodec = StorageCodec(),
) {
    public sealed class PendingRequestQueueStrategy {
        public class Squash(
            public val squashUpdateIntoCreate: SquashRequestMerger,
        ) : PendingRequestQueueStrategy()

        public object Queue : PendingRequestQueueStrategy()
    }

    public sealed class QueueResult {
        public object Stored : QueueResult()

        public class InvalidQueueRequest(public val errorMessage: String) : QueueResult()

        public object StoreFailed : QueueResult()
    }

    internal fun queueCreateRequest(
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

    internal fun queueUpdateRequest(
        data: O,
        idempotencyKey: String,
        updateRequest: HttpRequest,
        serverAttemptMadeForCurrentRequest: Boolean,
        updateContext: LocalStoreManager.UpdateContext.ValidUpdate<O>,
        requestTag: T,
    ): QueueResult {
        return when (updateContext) {
            is LocalStoreManager.UpdateContext.ValidUpdate.Squash -> {
                val pendingRequests = getPendingRequests(data.clientId)
                val latestPendingRequest = pendingRequests.lastOrNull()
                when (latestPendingRequest?.type) {
                    PendingSyncRequest.Type.VOID ->
                        QueueResult.InvalidQueueRequest("Cannot make updates after void!")

                    PendingSyncRequest.Type.CREATE -> {
                        replaceEntry(
                            latestPendingRequest.copy(
                                type = PendingSyncRequest.Type.CREATE,
                                idempotencyKey = latestPendingRequest.idempotencyKey,
                                request = updateContext.squashUpdateIntoCreate.merge(
                                    createRequest = latestPendingRequest.request,
                                    updateRequest = updateRequest,
                                ),
                                data = data,
                                // A pending create should never have base data. It is by
                                // definition the first request.
                                lastSyncedData = null,
                                requestTag = requestTag.value,
                            ),
                        )
                    }

                    PendingSyncRequest.Type.UPDATE -> {
                        replaceEntry(
                            latestPendingRequest.copy(
                                request = updateRequest,
                                requestTag = requestTag.value,
                            )
                        )
                    }

                    null -> {
                        // There are not pending updates so there is effectively no difference
                        // between squash and queue.
                        storeEntry(
                            pendingSyncRequest = PendingSyncRequest(
                                type = PendingSyncRequest.Type.UPDATE,
                                idempotencyKey = idempotencyKey,
                                request = updateRequest,
                                serverAttemptMade = false,
                                data = data,
                                lastSyncedData = updateContext.baseData,
                                requestTag = requestTag.value,
                            ),
                        )
                    }
                }
            }

            is LocalStoreManager.UpdateContext.ValidUpdate.Queue -> {
                if (getPendingRequests(data.clientId).any { it.type == PendingSyncRequest.Type.VOID }) {
                    QueueResult.InvalidQueueRequest("Cannot make updates after void!")
                } else {
                    storeEntry(
                        pendingSyncRequest = PendingSyncRequest(
                            type = PendingSyncRequest.Type.UPDATE,
                            idempotencyKey = idempotencyKey,
                            request = updateRequest,
                            serverAttemptMade = serverAttemptMadeForCurrentRequest,
                            data = data,
                            lastSyncedData = updateContext.baseData,
                            requestTag = requestTag.value,
                        ),
                    )
                }
            }
        }
    }

    internal fun queueVoidRequest(
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
            data_blob = storageCodec.encodeForStorage(codec.encodeToString(pendingSyncRequest.data)),
            type = pendingSyncRequest.type.value,
            request = storageCodec.encodeForStorage(pendingSyncRequest.request.toJson().toString()),
            idempotency_key = pendingSyncRequest.idempotencyKey,
            server_attempt_made = if (pendingSyncRequest.serverAttemptMade) 1L else 0L,
            conflict_info = null,
            last_synced_data = storageCodec.encodeForStorageOrNull(pendingSyncRequest.lastSyncedData?.let { codec.encodeToString(it) }),
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
            data_blob = storageCodec.encodeForStorage(codec.encodeToString(pendingSyncRequest.data)),
            type = pendingSyncRequest.type.value,
            request = storageCodec.encodeForStorage(pendingSyncRequest.request.toJson().toString()),
            idempotency_key = pendingSyncRequest.idempotencyKey,
            server_attempt_made = if (pendingSyncRequest.serverAttemptMade) 1L else 0L,
            conflict_info = storageCodec.encodeForStorageOrNull(pendingSyncRequest.conflict?.toJson(codec)?.toString()),
            last_synced_data = storageCodec.encodeForStorageOrNull(pendingSyncRequest.lastSyncedData?.let { codec.encodeToString(it) }),
            request_tag = pendingSyncRequest.requestTag,
            pending_request_id = pendingSyncRequest.pendingRequestId.toLong(),
        )
        status.refresh()
        QueueResult.Stored
    } catch (e: Exception) {
        QueueResult.StoreFailed
    }

    internal fun hasAnyPendingRequests(): Boolean =
        database.syncPendingEventsQueries.hasPendingRequestsByService(
            service_name = serviceName,
        ).executeAsOne()

    internal fun hasPendingRequests(clientId: String): Boolean =
        database.syncPendingEventsQueries.hasPendingRequestsByClientId(
            service_name = serviceName,
            client_id = clientId,
        ).executeAsOne()

    internal fun getLatestPendingRequest(clientId: String): PendingSyncRequest<O>? =
        getPendingRequests(clientId).lastOrNull()

    internal fun getPendingRequestById(pendingRequestId: Int): PendingSyncRequest<O>? {
        val row = database.syncPendingEventsQueries.getPendingRequestById(
            pending_request_id = pendingRequestId.toLong(),
        ).executeAsOneOrNull() ?: return null
        return mapRowToPendingSyncRequest(
            row.pending_request_id, row.type, row.idempotency_key,
            row.request, row.server_attempt_made, row.data_blob, row.conflict_info,
            row.last_synced_data, row.request_tag,
        )
    }

    internal fun getPendingRequests(clientId: String): List<PendingSyncRequest<O>> {
        return database.syncPendingEventsQueries.getPendingRequestsByClientId(
            service_name = serviceName,
            client_id = clientId,
        ).executeAsList().map { row ->
            mapRowToPendingSyncRequest(row.pending_request_id, row.type, row.idempotency_key,
                row.request, row.server_attempt_made, row.data_blob, row.conflict_info, row.last_synced_data,
                row.request_tag)
        }
    }

    internal fun getPendingRequests(): List<PendingSyncRequest<O>> {
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
        request = HttpRequest.fromJson(Json.parseToJsonElement(storageCodec.decodeFromStorage(request)).jsonObject),
        serverAttemptMade = serverAttemptMade != 0L,
        data = codec.decode(storageCodec.decodeFromStorage(data), SyncableObject.SyncStatus.LocalOnly),
        conflict = conflictInfo?.let {
            SyncableObjectRebaseHandler.FieldConflict.fromJson(
                jsonObject = Json.parseToJsonElement(storageCodec.decodeFromStorage(it)).jsonObject,
                codec = codec,
            )
        },
        lastSyncedData = lastSyncedData?.let {
            codec.decode(storageCodec.decodeFromStorage(it), SyncableObject.SyncStatus.Synced(""))
        },
        requestTag = requestTag,
    )

    internal fun clearAllPendingRequests(
        clientId: String,
    ) {
        database.syncPendingEventsQueries.clearAllByClientId(
            service_name = serviceName,
            client_id = clientId,
        )
        status.refresh()
    }

    internal fun clearPendingRequestAfterUpload(
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

    internal sealed class ClearRequestResult {
        internal class Cleared(internal val updatedSyncStatus: String) : ClearRequestResult()

        internal object FailedToRemoveEntry : ClearRequestResult()
    }

    internal fun markPendingRequestAsAttempted(pendingRequestId: Int) {
        database.syncPendingEventsQueries.markAsAttempted(
            pending_request_id = pendingRequestId.toLong(),
        )
    }

    internal fun rebaseDataForRemainingPendingRequests(
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

    internal sealed class RebasePendingRequestsResult<O : SyncableObject<O>> {
        internal class NoPendingRequestRemaining<O : SyncableObject<O>> : RebasePendingRequestsResult<O>()

        internal class RebasedRemainingPendingRequests<O : SyncableObject<O>>(
            internal val rebasedLatestData: O,
        ) : RebasePendingRequestsResult<O>()

        internal class AbortedRebaseToConflicts<O : SyncableObject<O>>(
            internal val conflict: SyncableObjectRebaseHandler.FieldConflict<O>,
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
                last_synced_data = storageCodec.encodeForStorage(codec.encodeToString(newBaseData)),
                data_blob = storageCodec.encodeForStorage(codec.encodeToString(rebasedData)),
                request = storageCodec.encodeForStorage(updatedRequest.toJson().toString()),
                pending_request_id = pendingRequestId.toLong(),
            )
        } else {
            database.syncPendingEventsQueries.rebasePendingSync(
                last_synced_data = storageCodec.encodeForStorage(codec.encodeToString(newBaseData)),
                data_blob = storageCodec.encodeForStorage(codec.encodeToString(rebasedData)),
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
                conflict_info = storageCodec.encodeForStorage(conflict.conflict.toJson(codec).toString()),
                pending_request_id = pendingSyncRequest.pendingRequestId.toLong(),
            )
            status.refresh()
        }
    }

    internal sealed class UpsertPendingChangesResult {
        internal object NoPendingRequests : UpsertPendingChangesResult()

        internal class MergedAllPendingChanges(internal val resolvedConflicts: Boolean) : UpsertPendingChangesResult()

        internal object PendingChangesConflict : UpsertPendingChangesResult()
    }

    /**
     * Returns the first pending request for [clientId] that has a non-null conflict,
     * or null if no conflicting request exists.
     */
    internal fun getConflictingPendingRequest(clientId: String): PendingSyncRequest<O>? =
        getPendingRequests(clientId).firstOrNull { it.conflict != null }

    /**
     * Resolves a conflict on a pending request by replacing its data and request with
     * the consumer-provided resolved values, clearing the conflict_info, and updating
     * the last_synced_data to the new server baseline.
     */
    internal fun resolveConflictOnPendingRequest(
        pendingRequest: PendingSyncRequest<O>,
        resolvedData: O,
        resolvedHttpRequest: HttpRequest,
        newServerBaseline: O,
    ) {
        database.syncPendingEventsQueries.resolveConflict(
            data_blob = storageCodec.encodeForStorage(codec.encodeToString(resolvedData)),
            request = storageCodec.encodeForStorage(resolvedHttpRequest.toJson().toString()),
            last_synced_data = storageCodec.encodeForStorage(codec.encodeToString(newServerBaseline)),
            pending_request_id = pendingRequest.pendingRequestId.toLong(),
        )
        status.refresh()
    }

    internal fun hasAnyConflictsGlobally(): Boolean =
        database.syncPendingEventsQueries.hasAnyConflicts().executeAsOne()

    internal fun getConflicts(clientId: String): List<SyncableObjectRebaseHandler.FieldConflict<O>> {
        return getPendingRequests(clientId).mapNotNull { pendingSyncRequest ->
            pendingSyncRequest.conflict
        }
    }

    internal companion object {
        internal const val TAG: String = "SyncableObjectService:PendingRequestQueueManager"
    }
}
