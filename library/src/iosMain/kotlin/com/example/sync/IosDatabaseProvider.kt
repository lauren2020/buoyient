package com.example.sync

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.example.sync.db.SyncDatabase

actual fun createSyncDatabase(): SyncDatabase = IosDatabaseProvider.database

object IosDatabaseProvider {
    val database: SyncDatabase by lazy {
        val driver = NativeSqliteDriver(SyncDatabase.Schema, "databuoy.db")
        SyncDatabase(driver)
    }
}
