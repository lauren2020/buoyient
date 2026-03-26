package com.les.databuoy

import com.les.databuoy.db.SyncDatabase

/**
 * Process-wide [SyncDatabase] override. When set, [LocalStoreManager] and other
 * internal components use this database instead of the platform default.
 *
 * Set via [DataBuoy.database] for custom database configurations or by
 * [TestServiceEnvironment] for integration tests (in-memory database isolation).
 */
object DatabaseOverride {
    @Volatile
    var database: SyncDatabase? = null
}
