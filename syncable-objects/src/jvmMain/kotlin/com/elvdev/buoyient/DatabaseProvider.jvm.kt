package com.elvdev.buoyient.globalconfigs

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.elvdev.buoyient.db.SyncDatabase

public actual fun createSyncDatabase(): SyncDatabase = DatabaseProvider.handle.database
public actual fun createSyncDatabaseHandle(): SyncDatabaseHandle = DatabaseProvider.handle

public object DatabaseProvider {
    public val handle: SyncDatabaseHandle by lazy {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SyncDatabase.Schema.create(driver)
        SyncDatabaseHandle(database = SyncDatabase(driver), driver = driver)
    }

    public val database: SyncDatabase get() = handle.database
}
