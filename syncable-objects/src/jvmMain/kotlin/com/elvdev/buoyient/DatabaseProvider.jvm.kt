package com.elvdev.buoyient.globalconfigs

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.elvdev.buoyient.db.SyncDatabase

public actual fun createSyncDatabase(): SyncDatabase = DatabaseProvider.database

public object DatabaseProvider {
    public val database: SyncDatabase by lazy {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SyncDatabase.Schema.create(driver)
        SyncDatabase(driver)
    }
}
