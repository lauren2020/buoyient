package com.les.databuoy.globalconfigs

import com.les.databuoy.db.SyncDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observable, app-wide view of pending sync health across every registered service.
 */
public class DataBuoyStatus(
    private val database: SyncDatabase = createSyncDatabase(),
) {
    private val _hasPendingConflicts = MutableStateFlow(false)
    public val hasPendingConflicts: StateFlow<Boolean> = _hasPendingConflicts.asStateFlow()

    private val _pendingRequestCount = MutableStateFlow(0)
    public val pendingRequestCount: StateFlow<Int> = _pendingRequestCount.asStateFlow()

    init {
        refresh()
    }

    public fun refresh() {
        _hasPendingConflicts.value = database.syncPendingEventsQueries.hasAnyConflicts().executeAsOne()
        _pendingRequestCount.value = database.syncPendingEventsQueries.getPendingRequestCount().executeAsOne().toInt()
    }

    public companion object {
        public val shared: DataBuoyStatus by lazy { DataBuoyStatus() }
    }
}
