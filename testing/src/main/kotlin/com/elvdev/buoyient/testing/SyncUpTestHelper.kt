package com.elvdev.buoyient.testing

import com.elvdev.buoyient.globalconfigs.DatabaseOverride
import com.elvdev.buoyient.sync.SyncUpCoordinator
import com.elvdev.buoyient.SyncableObjectService
import com.elvdev.buoyient.globalconfigs.createSyncDatabase

/**
 * Test helper that runs a single-service sync-up pass via [SyncUpCoordinator].
 *
 * Use this in tests where you need to trigger sync-up for one service. In
 * production, [SyncUpCoordinator.syncUpAll] is called by `SyncWorker` with
 * all registered drivers for global ordering.
 *
 * The database is resolved from [DatabaseOverride] (set by [TestServiceEnvironment])
 * or falls back to the platform default.
 *
 * @return the number of pending requests that were successfully synced.
 */
public suspend fun SyncableObjectService<*, *>.syncUpLocalChanges(): Int {
    val database = DatabaseOverride.database ?: createSyncDatabase()
    val coordinator = SyncUpCoordinator(
        drivers = listOf(this.syncDriver),
        database = database,
    )
    return coordinator.syncUpAll()
}
