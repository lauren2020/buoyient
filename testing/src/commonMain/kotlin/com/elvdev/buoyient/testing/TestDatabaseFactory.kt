package com.elvdev.buoyient.testing

import com.elvdev.buoyient.db.SyncDatabase

/**
 * Factory for creating in-memory [SyncDatabase] instances for tests.
 * Each call to [createInMemory] returns an isolated database with no shared state,
 * ensuring tests do not interfere with each other.
 */
public expect object TestDatabaseFactory {

    /**
     * Creates a fresh in-memory [SyncDatabase] with the schema fully applied.
     */
    public fun createInMemory(): SyncDatabase
}
