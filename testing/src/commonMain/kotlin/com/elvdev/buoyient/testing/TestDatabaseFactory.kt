package com.elvdev.buoyient.testing

import com.elvdev.buoyient.db.SyncDatabase
import com.elvdev.buoyient.globalconfigs.SyncDatabaseHandle

/**
 * Factory for creating in-memory [SyncDatabase] instances for tests.
 * Each call returns an isolated database with no shared state, ensuring tests do
 * not interfere with each other.
 */
public expect object TestDatabaseFactory {

    /**
     * Creates a fresh in-memory [SyncDatabase] with the schema fully applied.
     */
    public fun createInMemory(): SyncDatabase

    /**
     * Creates a fresh in-memory database paired with its [SqlDriver]. Required
     * when the test exercises filter-based queries that drop to dynamic SQL.
     */
    public fun createInMemoryHandle(): SyncDatabaseHandle
}
