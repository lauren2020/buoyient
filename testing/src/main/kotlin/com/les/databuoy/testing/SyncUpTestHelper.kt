package com.les.databuoy.testing

import com.les.databuoy.SyncLogger
import com.les.databuoy.SyncUpCoordinator
import com.les.databuoy.SyncableObjectService
import com.les.databuoy.db.SyncDatabase

/**
 * Test helper that runs a single-service sync-up pass via [SyncUpCoordinator].
 *
 * Use this in tests where you need to trigger sync-up for one service. In
 * production, [SyncUpCoordinator.syncUpAll] is called by `SyncWorker` with
 * all registered services for global ordering.
 *
 * @param database the [SyncDatabase] backing the service (typically
 *   [TestServiceEnvironment.database]).
 * @param logger the [SyncLogger] for the coordinator. Defaults to [NoOpSyncLogger].
 *   Pass a `PrintSyncLogger` to see sync-up activity during debugging.
 * @return the number of pending requests that were successfully synced.
 */
suspend fun SyncableObjectService<*, *>.syncUpLocalChanges(
    database: SyncDatabase,
    logger: SyncLogger = NoOpSyncLogger,
): Int {
    val coordinator = SyncUpCoordinator(
        participants = listOf(this),
        database = database,
        logger = logger,
    )
    return coordinator.syncUpAll()
}
