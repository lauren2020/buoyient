package com.les.databuoy

actual fun createPlatformSyncScheduleNotifier(): SyncScheduleNotifier = object : SyncScheduleNotifier {
    override fun scheduleSyncIfNeeded() { /* no-op for JVM */ }
}
