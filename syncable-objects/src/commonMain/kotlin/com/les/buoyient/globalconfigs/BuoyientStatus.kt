package com.les.buoyient.globalconfigs

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOne
import com.les.buoyient.db.SyncDatabase
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Observable, app-wide view of pending sync health across every registered service.
 *
 * Backed by SQLDelight reactive queries — automatically updates whenever the
 * `sync_pending_events` table changes. No manual refresh needed.
 */
public class BuoyientStatus(
    private val database: SyncDatabase = createSyncDatabase(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val queryContext: CoroutineContext = scope.coroutineContext

    public val hasPendingConflicts: StateFlow<Boolean> =
        database.syncPendingEventsQueries.hasAnyConflicts()
            .asFlow().mapToOne(queryContext)
            .stateIn(scope, SharingStarted.Eagerly, false)

    public val pendingRequestCount: StateFlow<Int> =
        database.syncPendingEventsQueries.getPendingRequestCount()
            .asFlow().mapToOne(queryContext)
            .map { it.toInt() }
            .stateIn(scope, SharingStarted.Eagerly, 0)

    public companion object {
        public val shared: BuoyientStatus by lazy { BuoyientStatus() }
    }
}
