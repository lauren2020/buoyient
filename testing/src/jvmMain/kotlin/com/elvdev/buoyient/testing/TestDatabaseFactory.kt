package com.elvdev.buoyient.testing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.elvdev.buoyient.db.SyncDatabase
import com.elvdev.buoyient.globalconfigs.SyncDatabaseHandle

/**
 * Factory for creating in-memory [SyncDatabase] instances for tests.
 * Each call returns an isolated database with no shared state, ensuring tests do
 * not interfere with each other.
 */
public actual object TestDatabaseFactory {

    public actual fun createInMemory(): SyncDatabase = createInMemoryHandle().database

    public actual fun createInMemoryHandle(): SyncDatabaseHandle {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SyncDatabase.Schema.create(driver)
        return SyncDatabaseHandle(database = SyncDatabase(driver), driver = driver)
    }
}
