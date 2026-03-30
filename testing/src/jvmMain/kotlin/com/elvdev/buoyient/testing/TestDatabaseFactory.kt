package com.elvdev.buoyient.testing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.elvdev.buoyient.db.SyncDatabase

/**
 * Factory for creating in-memory [SyncDatabase] instances for tests.
 * Each call to [createInMemory] returns an isolated database with no shared state,
 * ensuring tests do not interfere with each other.
 */
public actual object TestDatabaseFactory {

    /**
     * Creates a fresh in-memory [SyncDatabase] with the schema fully applied.
     */
    public actual fun createInMemory(): SyncDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SyncDatabase.Schema.create(driver)
        return SyncDatabase(driver)
    }
}
