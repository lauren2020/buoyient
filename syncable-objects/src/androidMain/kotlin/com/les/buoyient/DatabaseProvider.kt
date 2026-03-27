package com.les.buoyient.globalconfigs

import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.les.buoyient.BuoyientPlatformContext
import com.les.buoyient.db.SyncDatabase

public actual fun createSyncDatabase(): SyncDatabase = DatabaseProvider.database

public object DatabaseProvider {
    /**
     * Application-scoped singleton database. The database lives as long as
     * the process does — Android will tear down the process (and the
     * underlying SQLite connection) when the OS reclaims resources, so no
     * explicit close() is needed.
     */
    public val database: SyncDatabase by lazy {
        val driver = AndroidSqliteDriver(
            SyncDatabase.Schema,
            BuoyientPlatformContext.appContext,
            "buoyient.db",
        )
        SyncDatabase(driver)
    }
}
