package com.elvdev.buoyient.globalconfigs

import com.elvdev.buoyient.db.SyncDatabase
import kotlin.concurrent.Volatile

/**
 * Process-wide [SyncDatabase] override. When set, [com.elvdev.buoyient.managers.LocalStoreManager] and other
 * internal components use this database instead of the platform default.
 *
 * Set via [Buoyient.database] for custom database configurations or by
 * [TestServiceEnvironment] for integration tests (in-memory database isolation).
 */
public object DatabaseOverride {
    @Volatile
    public var database: SyncDatabase? = null
}
