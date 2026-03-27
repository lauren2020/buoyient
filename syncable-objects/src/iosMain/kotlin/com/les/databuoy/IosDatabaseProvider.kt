package com.les.databuoy.globalconfigs

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.les.databuoy.db.SyncDatabase

public actual fun createSyncDatabase(): SyncDatabase = IosDatabaseProvider.database

public object IosDatabaseProvider {
    public val database: SyncDatabase by lazy {
        val driver = NativeSqliteDriver(SyncDatabase.Schema, "databuoy.db")
        SyncDatabase(driver)
    }
}
