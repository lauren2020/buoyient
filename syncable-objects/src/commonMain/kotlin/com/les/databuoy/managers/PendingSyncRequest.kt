package com.les.databuoy.managers

import com.les.databuoy.syncableobjectservicedatatypes.HttpRequest
import com.les.databuoy.SyncableObject
import com.les.databuoy.serviceconfigs.SyncableObjectRebaseHandler

/**
 * Represents a pending sync work item stored in the database. This
 * consolidates all the metadata needed to replay an offline operation
 * when connectivity returns: the request body, the target endpoint URL,
 * the idempotency key, and optionally the sparse data for partial updates.
 *
 * Stored as a single JSON TEXT column (`pending_sync_request`) — when
 * the column is NULL the row has no pending work.
 *
 * @param O the [com.les.databuoy.SyncableObject] type this request operates on.
 * @property pendingRequestId database row ID for this pending request, or `-1` if not yet persisted.
 * @property type the operation type ([Type.CREATE], [Type.UPDATE], or [Type.VOID]).
 * @property idempotencyKey unique key used to deduplicate retries of the same operation on the server.
 * @property request the [HttpRequest] to replay when connectivity returns.
 * @property serverAttemptMade whether a sync-up attempt has been attempted for this request. If
 *  a sync-up attempt was sent and was accepted as completed this row would just be removed, but
 *  if a sync-up attempt was sent and failed as being unconfirmed if it processed
 *  (i.e. network timeout, server error), the row will be kept and this value will be set to `true`.
 * @property data the current local state of the object associated with this request.
 * @property baseData the latest state at the time the offline edit was made. If no requests were
 *  pending at the time of this update, this will reflect the last synced server data. If other
 *  requests were already pending, this will reflect the latest data state off the last update.
 *  This is used for three-way merge conflict detection. `null` for create operations since creates
 *  are by definition instantiating something new that does not exist yet.
 * @property conflict any field-level conflict detected during rebase, or `null` if no conflict exists.
 * @property clientId the canonical client ID from the `sync_pending_events.client_id` column.
 *   This is set at insert time and is **never modified by rebase**, unlike [data]`.clientId`
 *   which can be corrupted if the server response contains a different client ID.
 *   Always prefer this property over [data]`.clientId` when identifying the sync_data row.
 * @property requestTag the [com.les.databuoy.ServiceRequestTag.tagValue] identifying which operation produced this request.
 */
public data class PendingSyncRequest<O : SyncableObject<O>>(
    public val pendingRequestId: Int = -1,
    public val clientId: String = "",
    public val type: Type,
    public val idempotencyKey: String,
    public val request: HttpRequest,
    public val serverAttemptMade: Boolean,
    public val data: O,
    public val baseData: O?,
    public val conflict: SyncableObjectRebaseHandler.FieldConflict<O>? = null,
    public val requestTag: String,
) {
    public enum class Type(public val value: String) {
        CREATE("CREATE"),
        UPDATE("UPDATE"),
        VOID("VOID");

        public companion object {
            @OptIn(ExperimentalStdlibApi::class)
            public fun fromValue(value: String): Type = entries.first { it.value == value }
        }
    }
}