package com.elvdev.buoyient.testing

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.elvdev.buoyient.db.SyncDatabase
import com.elvdev.buoyient.globalconfigs.SyncDatabaseHandle

public actual object TestDatabaseFactory {

    public actual fun createInMemory(): SyncDatabase = createInMemoryHandle().database

    public actual fun createInMemoryHandle(): SyncDatabaseHandle {
        val driver = NativeSqliteDriver(SyncDatabase.Schema, ":memory:")
        return SyncDatabaseHandle(database = SyncDatabase(driver), driver = driver)
    }
}
