package com.elvdev.buoyient.testing

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.elvdev.buoyient.db.SyncDatabase

public actual object TestDatabaseFactory {

    public actual fun createInMemory(): SyncDatabase {
        val driver = NativeSqliteDriver(SyncDatabase.Schema, ":memory:")
        return SyncDatabase(driver)
    }
}
