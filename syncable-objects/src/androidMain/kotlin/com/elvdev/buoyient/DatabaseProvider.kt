package com.elvdev.buoyient.globalconfigs

import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.elvdev.buoyient.BuoyientPlatformContext
import com.elvdev.buoyient.db.SyncDatabase

public actual fun createSyncDatabase(): SyncDatabase = DatabaseProvider.handle.database
public actual fun createSyncDatabaseHandle(): SyncDatabaseHandle = DatabaseProvider.handle

public object DatabaseProvider {
    /**
     * Application-scoped singleton database + driver. The connection lives as long
     * as the process does — Android tears down the process (and the underlying
     * SQLite connection) when the OS reclaims resources, so no explicit close()
     * is needed.
     */
    public val handle: SyncDatabaseHandle by lazy {
        val driver = AndroidSqliteDriver(
            SyncDatabase.Schema,
            BuoyientPlatformContext.appContext,
            "buoyient.db",
        )
        SyncDatabaseHandle(database = SyncDatabase(driver), driver = driver)
    }

    public val database: SyncDatabase get() = handle.database
}
