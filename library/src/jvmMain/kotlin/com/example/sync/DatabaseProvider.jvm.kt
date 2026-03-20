package com.example.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.sync.db.SyncDatabase

actual fun createSyncDatabase(): SyncDatabase {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    SyncDatabase.Schema.create(driver)
    return SyncDatabase(driver)
}
