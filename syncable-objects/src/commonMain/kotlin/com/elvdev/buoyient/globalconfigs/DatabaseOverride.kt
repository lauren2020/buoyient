package com.elvdev.buoyient.globalconfigs

import app.cash.sqldelight.db.SqlDriver
import com.elvdev.buoyient.db.SyncDatabase
import kotlin.concurrent.Volatile

/**
 * Process-wide [SyncDatabase] override. When [database] is set, [com.elvdev.buoyient.managers.LocalStoreManager]
 * and other internal components use this database instead of the platform default.
 *
 * Set via [Buoyient.database] / [Buoyient.databaseHandle] for custom database
 * configurations or by [TestServiceEnvironment] for integration tests (in-memory
 * database isolation).
 *
 * **Driver pairing.** Filter-based queries (e.g. `loadPage(filter = ...)`) drop
 * to dynamic SQL via the raw [SqlDriver], so callers that override [database]
 * should also set [driver] to the matching driver — otherwise filter operations
 * throw at runtime. Set both via [Buoyient.databaseHandle] to avoid mismatches.
 */
public object DatabaseOverride {
    @Volatile
    public var database: SyncDatabase? = null

    @Volatile
    public var driver: SqlDriver? = null
}
