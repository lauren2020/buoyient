package com.les.buoyient.sync

public actual fun createPlatformSyncScheduleNotifier(): SyncScheduleNotifier = object :
    SyncScheduleNotifier {
    override fun scheduleSyncIfNeeded() { /* no-op for JVM */ }
}
