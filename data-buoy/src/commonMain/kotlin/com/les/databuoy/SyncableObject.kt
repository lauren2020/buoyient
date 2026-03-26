package com.les.databuoy

public interface SyncableObject<O> {
    /**
     * The id the server is aware of. This value should be null if
     * and only if no version of this object has been synced to the
     * server.
     */
    public val serverId: String?

    /**
     * The id the client is aware of. This value serves as an always
     * reliable client identifier for this object regardless of server
     * sync status.
     */
    public val clientId: String

    /**
     * The current version of this object. Any time an update is
     * submitted - on or offline - the version will be incremented.
     */
    public val version: String

    public val syncStatus: SyncStatus

    /**
     * Returns a copy of this object with the given [syncStatus].
     * Implementations on data classes can simply delegate to `copy(syncStatus = syncStatus)`.
     */
    public fun withSyncStatus(syncStatus: SyncStatus): O

    public companion object {
        public const val LAST_SYNCED_TIMESTAMP_KEY: String = "last_synced_timestamp"
        public const val SERVER_ID_KEY: String = "server_id"
        public const val CLIENT_ID_KEY: String = "client_id"
        public const val VERSION_KEY: String = "version"
        public const val SYNC_STATUS_KEY: String = "sync_status"
        public const val PENDING_SYNC_REQUEST_KEY: String = "pending_sync_request"
    }

    public sealed class SyncStatus(public val status: String) {
        public abstract val lastSyncedTimestamp: String?

        public object LocalOnly : SyncStatus(LOCAL_ONLY) {
            public override val lastSyncedTimestamp: String? = null
        }

        public object PendingCreate : SyncStatus(PENDING_CREATE) {
            public override val lastSyncedTimestamp: String? = null
        }

        public class PendingUpdate(
            public override val lastSyncedTimestamp: String?,
        ) : SyncStatus(PENDING_UPDATE)

        public class Synced(
            public override val lastSyncedTimestamp: String,
        ) : SyncStatus(SYNCED)

        public class PendingVoid(
            public override val lastSyncedTimestamp: String?,
        ) : SyncStatus(Companion.PENDING_VOID)

        public class Conflict(
            public override val lastSyncedTimestamp: String,
            public val conflictInfo: List<FieldConflictInfo>,
        ) : SyncStatus(CONFLICT) {
            public data class FieldConflictInfo(
                public val fieldName: String,
                public val baseValue: String?,
                public val localValue: String?,
                public val serverValue: String?,
            )
        }

        public companion object {
            public const val LOCAL_ONLY: String = "LOCAL_ONLY"
            public const val PENDING_CREATE: String = "PENDING_CREATE"
            public const val PENDING_UPDATE: String = "PENDING_UPDATE"
            public const val PENDING_VOID: String = "PENDING_VOID"
            public const val SYNCED: String = "SYNCED"
            public const val CONFLICT: String = "CONFLICT"
            public fun buildFromDbContext(
                status: String,
                lastSyncedTimestamp: String?,
                conflictInfo: List<Conflict.FieldConflictInfo>,
            ): SyncStatus = when (status) {
                LOCAL_ONLY -> LocalOnly

                PENDING_CREATE -> PendingCreate

                PENDING_UPDATE -> PendingUpdate(
                    lastSyncedTimestamp = lastSyncedTimestamp,
                )

                PENDING_VOID -> PendingVoid(
                    lastSyncedTimestamp = lastSyncedTimestamp,
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
