package com.elvdev.buoyient.globalconfigs

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.elvdev.buoyient.db.SyncDatabase

public actual fun createSyncDatabase(): SyncDatabase = IosDatabaseProvider.handle.database
public actual fun createSyncDatabaseHandle(): SyncDatabaseHandle = IosDatabaseProvider.handle

public object IosDatabaseProvider {
    public val handle: SyncDatabaseHandle by lazy {
        val driver = NativeSqliteDriver(SyncDatabase.Schema, "buoyient.db")
        SyncDatabaseHandle(database = SyncDatabase(driver), driver = driver)
    }

    public val database: SyncDatabase get() = handle.database
}
