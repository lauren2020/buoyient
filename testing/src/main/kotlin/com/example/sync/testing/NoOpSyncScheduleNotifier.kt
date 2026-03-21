package com.example.sync.testing

import com.example.sync.SyncScheduleNotifier

/**
 * A [SyncScheduleNotifier] that does nothing. In tests there is no WorkManager
 * or background scheduler to notify, so this stub prevents side effects.
 */
object NoOpSyncScheduleNotifier : SyncScheduleNotifier {
    override fun scheduleSyncIfNeeded() {}
}
