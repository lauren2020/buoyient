package com.les.databuoy.globalconfigs

import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.les.databuoy.DataBuoyPlatformContext
import com.les.databuoy.db.SyncDatabase

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
            DataBuoyPlatformContext.appContext,
            "databuoy.db",
        )
        SyncDatabase(driver)
    }
}
