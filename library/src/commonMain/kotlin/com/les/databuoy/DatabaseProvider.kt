package com.les.databuoy

import com.les.databuoy.db.SyncDatabase

/**
 * Returns the platform-specific, application-scoped [SyncDatabase] singleton.
 * On Android this uses [AndroidSqliteDriver]; on iOS this uses [NativeSqliteDriver].
 */
expect fun createSyncDatabase(): SyncDatabase
