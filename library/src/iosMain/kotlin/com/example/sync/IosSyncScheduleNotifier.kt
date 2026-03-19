package com.example.sync

actual fun createPlatformSyncScheduleNotifier(): SyncScheduleNotifier = IosSyncScheduleNotifier()

class IosSyncScheduleNotifier : SyncScheduleNotifier {
    override fun scheduleSyncIfNeeded() {
        // TODO: Implement iOS background task scheduling (e.g. BGTaskScheduler)
    }
}
