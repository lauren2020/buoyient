package com.les.databuoy.sync

import android.content.Context
import com.les.databuoy.DataBuoyPlatformContext
import com.les.databuoy.SyncScheduler

public actual fun createPlatformSyncScheduleNotifier(): SyncScheduleNotifier =
    AndroidSyncScheduleNotifier(DataBuoyPlatformContext.appContext)

public class AndroidSyncScheduleNotifier(private val context: Context) : SyncScheduleNotifier {
    override fun scheduleSyncIfNeeded() {
        SyncScheduler.scheduleSyncIfNeeded(context)
    }
}
