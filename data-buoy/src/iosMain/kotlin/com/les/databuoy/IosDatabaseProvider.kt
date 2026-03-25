package com.les.databuoy

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.les.databuoy.db.SyncDatabase

actual fun createSyncDatabase(): SyncDatabase = IosDatabaseProvider.database

object IosDatabaseProvider {
    val database: SyncDatabase by lazy {
        val driver = NativeSqliteDriver(SyncDatabase.Schema, "databuoy.db")
        SyncDatabase(driver)
    }
}
