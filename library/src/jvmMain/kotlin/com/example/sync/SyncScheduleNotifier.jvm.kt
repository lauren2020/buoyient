package com.example.sync

actual fun createPlatformSyncScheduleNotifier(): SyncScheduleNotifier = object : SyncScheduleNotifier {
    override fun scheduleSyncIfNeeded() { /* no-op for JVM */ }
}
