package com.elvdev.buoyient.globalconfigs

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.elvdev.buoyient.db.SyncDatabase

public actual fun createSyncDatabase(): SyncDatabase = IosDatabaseProvider.database

public object IosDatabaseProvider {
    public val database: SyncDatabase by lazy {
        val driver = NativeSqliteDriver(SyncDatabase.Schema, "buoyient.db")
        SyncDatabase(driver)
    }
}
