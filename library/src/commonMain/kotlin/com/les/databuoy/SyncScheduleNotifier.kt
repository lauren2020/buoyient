package com.les.databuoy

/**
 * Platform-agnostic interface for notifying the system that a background sync should be scheduled.
 * On Android, this wraps WorkManager scheduling. On iOS, this could trigger a background task.
 */
interface SyncScheduleNotifier {
    fun scheduleSyncIfNeeded()
}

/**
 * Returns the platform-specific [SyncScheduleNotifier] implementation.
 * On Android this uses WorkManager; on iOS this is a stub for BGTaskScheduler.
 */
expect fun createPlatformSyncScheduleNotifier(): SyncScheduleNotifier
