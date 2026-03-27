package com.les.databuoy.globalconfigs

import com.les.databuoy.db.SyncDatabase

/**
 * Returns the platform-specific, application-scoped [SyncDatabase] singleton.
 * On Android this uses [AndroidSqliteDriver]; on iOS this uses [NativeSqliteDriver].
 */
public expect fun createSyncDatabase(): SyncDatabase
