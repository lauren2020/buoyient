package com.les.databuoy.testing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.les.databuoy.db.SyncDatabase

/**
 * Factory for creating in-memory [SyncDatabase] instances for tests.
 * Each call to [createInMemory] returns an isolated database with no shared state,
 * ensuring tests do not interfere with each other.
 */
public object TestDatabaseFactory {

    /**
     * Creates a fresh in-memory [SyncDatabase] with the schema fully applied.
     */
    public fun createInMemory(): SyncDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SyncDatabase.Schema.create(driver)
        return SyncDatabase(driver)
    }
}
