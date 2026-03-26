package com.les.databuoy

public actual fun createPlatformSyncScheduleNotifier(): SyncScheduleNotifier = object : SyncScheduleNotifier {
    override fun scheduleSyncIfNeeded() { /* no-op for JVM */ }
}
