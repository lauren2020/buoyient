package com.example.sync

interface SyncableObject<O> {
    /**
     * The id the server is aware of. This value should be null if
     * and only if some version of this object has been synced to the
     * server.
     */
    val serverId: String?

    /**
     * The id the client is aware of. This value serves as an always
     * reliable client identifier for this object regardless of server
     * sync status.
     */
    val clientId: String

    /**
     * The current version of this object. Any time an update is
     * submitted - on or offline - the version will be incremented.
     */
    val version: Int

    val syncStatus: SyncStatus

    /**
     * Returns a copy of this object with the given [syncStatus].
     * Implementations on data classes can simply delegate to `copy(syncStatus = syncStatus)`.
     */
    fun withSyncStatus(syncStatus: SyncStatus): O

    companion object {
        const val LAST_SYNCED_TIMESTAMP_KEY = "last_synced_timestamp"
        const val SERVER_ID_KEY = "server_id"
        const val CLIENT_ID_KEY = "client_id"
        const val VERSION_KEY = "version"
        const val SYNC_STATUS_KEY = "sync_status"
        const val PENDING_SYNC_REQUEST_KEY = "pending_sync_request"
    }

    sealed class SyncStatus(val status: String) {
        object LocalOnly : SyncStatus(LOCAL_ONLY)

        object PendingCreate : SyncStatus(PENDING_CREATE)

        class PendingUpdate(val lastSyncedTimestamp: String) : SyncStatus(PENDING_UPDATE)

        class Synced(val lastSyncedTimestamp: String) : SyncStatus(SYNCED)

        class PendingVoid(val lastSyncedTimestamp: String) : SyncStatus(Companion.PENDING_VOID)

        class Conflict(
            val lastSyncedTimestamp: String,
            val conflictInfo: List<FieldConflictInfo>,
        ) : SyncStatus(CONFLICT) {
            data class FieldConflictInfo(
                val fieldName: String,
                val baseValue: String?,
                val localValue: String?,
                val serverValue: String?,
            )
        }

        companion object {
            const val LOCAL_ONLY = "LOCAL_ONLY"
            const val PENDING_CREATE = "PENDING_CREATE"
            const val PENDING_UPDATE = "PENDING_UPDATE"
            const val PENDING_VOID = "PENDING_VOID"
            const val SYNCED = "SYNCED"
            const val CONFLICT = "CONFLICT"
            fun buildFromDbContext(
                status: String,
                lastSyncedTimestamp: String?,
                conflictInfo: List<Conflict.FieldConflictInfo>,
            ): SyncStatus = when (status) {
                LOCAL_ONLY -> LocalOnly

                PENDING_CREATE -> PendingCreate

                PENDING_UPDATE -> PendingUpdate(
                    lastSyncedTimestamp = lastSyncedTimestamp!!,
                )

                PENDING_VOID -> PendingVoid(
                    lastSyncedTimestamp = lastSyncedTimestamp!!,
                )

                SYNCED -> Synced(lastSyncedTimestamp = lastSyncedTimestamp!!)

                CONFLICT -> Conflict(
                    lastSyncedTimestamp = lastSyncedTimestamp!!,
                    conflictInfo = conflictInfo,
                )

                else -> error("Attempted to unpack db context with unknown sync status: $status")
            }
        }
    }
}
