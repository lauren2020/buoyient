package com.les.databuoy

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.les.databuoy.db.SyncDatabase

actual fun createSyncDatabase(): SyncDatabase {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    SyncDatabase.Schema.create(driver)
    return SyncDatabase(driver)
}
