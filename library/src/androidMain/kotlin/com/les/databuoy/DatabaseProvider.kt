package com.les.databuoy

import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.les.databuoy.db.SyncDatabase

actual fun createSyncDatabase(): SyncDatabase = DatabaseProvider.database

object DatabaseProvider {
    /**
     * Application-scoped singleton database. The database lives as long as
     * the process does — Android will tear down the process (and the
     * underlying SQLite connection) when the OS reclaims resources, so no
     * explicit close() is needed.
     */
    val database: SyncDatabase by lazy {
        val driver = AndroidSqliteDriver(
            SyncDatabase.Schema,
            DataBuoyPlatformContext.appContext,
            "databuoy.db",
        )
        SyncDatabase(driver)
    }
}
