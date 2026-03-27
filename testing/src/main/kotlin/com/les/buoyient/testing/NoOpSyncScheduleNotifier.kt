package com.les.buoyient.testing

import com.les.buoyient.sync.SyncScheduleNotifier

/**
 * A [SyncScheduleNotifier] that does nothing. In tests there is no WorkManager
 * or background scheduler to notify, so this stub prevents side effects.
 */
public object NoOpSyncScheduleNotifier : SyncScheduleNotifier {
    override fun scheduleSyncIfNeeded() {}
}
