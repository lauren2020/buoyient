package com.les.buoyient.sync

import android.content.Context
import com.les.buoyient.BuoyientPlatformContext
import com.les.buoyient.SyncScheduler

public actual fun createPlatformSyncScheduleNotifier(): SyncScheduleNotifier =
    AndroidSyncScheduleNotifier(BuoyientPlatformContext.appContext)

public class AndroidSyncScheduleNotifier(private val context: Context) : SyncScheduleNotifier {
    override fun scheduleSyncIfNeeded() {
        SyncScheduler.scheduleSyncIfNeeded(context)
    }
}
