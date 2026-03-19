package com.example.sync

import android.content.Context

actual fun createPlatformSyncScheduleNotifier(): SyncScheduleNotifier =
    AndroidSyncScheduleNotifier(DataBuoyPlatformContext.appContext)

class AndroidSyncScheduleNotifier(private val context: Context) : SyncScheduleNotifier {
    override fun scheduleSyncIfNeeded() {
        SyncScheduler.scheduleSyncIfNeeded(context)
    }
}
