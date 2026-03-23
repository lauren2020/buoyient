package com.les.databuoy

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.les.databuoy.db.SyncDatabase

actual fun createSyncDatabase(): SyncDatabase = DatabaseProvider.database

object DatabaseProvider {
    val database: SyncDatabase by lazy {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SyncDatabase.Schema.create(driver)
        SyncDatabase(driver)
    }
}
