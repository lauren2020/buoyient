package com.elvdev.buoyient.globalconfigs

import com.elvdev.buoyient.db.SyncDatabase

/**
 * Returns the platform-specific, application-scoped [SyncDatabase] singleton.
 * On Android this uses [AndroidSqliteDriver]; on iOS this uses [NativeSqliteDriver].
 */
public expect fun createSyncDatabase(): SyncDatabase

/**
 * Returns the platform-specific, application-scoped [SyncDatabaseHandle] (database +
 * driver) singleton. Both [createSyncDatabase] and this function delegate to the
 * same underlying lazy initializer per-platform — so the [SyncDatabase] returned
 * by one call and the [SyncDatabaseHandle.driver] returned by another are always
 * the same connection.
 */
public expect fun createSyncDatabaseHandle(): SyncDatabaseHandle
