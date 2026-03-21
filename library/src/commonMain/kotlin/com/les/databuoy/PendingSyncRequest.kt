package com.les.databuoy

/**
 * Represents a pending sync work item stored in the database. This
 * consolidates all the metadata needed to replay an offline operation
 * when connectivity returns: the request body, the target endpoint URL,
 * the idempotency key, and optionally the sparse data for partial updates.
 *
 * Stored as a single JSON TEXT column (`pending_sync_request`) — when
 * the column is NULL the row has no pending work.
 */
data class PendingSyncRequest<O : SyncableObject<O>>(
    val pendingRequestId: Int = -1,
    val type: Type,
    val idempotencyKey: String,
    val request: HttpRequest,
    val serverAttemptMade: Boolean,
    val data: O,
    val lastSyncedData: O?,
    val conflict: SyncableObjectMergeHandler.FieldConflict<O>? = null,
    val requestTag: String,
) {
    enum class Type(val value: String) {
        CREATE("CREATE"),
        UPDATE("UPDATE"),
        VOID("VOID");

        companion object {
            @OptIn(ExperimentalStdlibApi::class)
            fun fromValue(value: String): Type = entries.first { it.value == value }
        }
    }

    companion object {
        private const val PENDING_REQUEST_ID_KEY = "pending_request_id"
        private const val TYPE_KEY = "type"
        private const val HTTP_REQUEST_KEY = "http_request"
        private const val IDEMPOTENCY_KEY = "idempotency_key"
        private const val SERVER_ATTEMPT_MADE_KEY = "server_attempt_made"
        private const val DATA_KEY = "data"
        private const val CONFLICT_INFO_KEY = "conflict_info"
    }
}
