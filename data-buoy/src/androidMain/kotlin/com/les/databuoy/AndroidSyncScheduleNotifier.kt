package com.les.databuoy

import android.content.Context

public actual fun createPlatformSyncScheduleNotifier(): SyncScheduleNotifier =
    AndroidSyncScheduleNotifier(DataBuoyPlatformContext.appContext)

public class AndroidSyncScheduleNotifier(private val context: Context) : SyncScheduleNotifier {
    override fun scheduleSyncIfNeeded() {
        SyncScheduler.scheduleSyncIfNeeded(context)
    }
}
