package com.les.buoyient.globalconfigs

import com.les.buoyient.db.SyncDatabase

/**
 * Returns the platform-specific, application-scoped [SyncDatabase] singleton.
 * On Android this uses [AndroidSqliteDriver]; on iOS this uses [NativeSqliteDriver].
 */
public expect fun createSyncDatabase(): SyncDatabase
