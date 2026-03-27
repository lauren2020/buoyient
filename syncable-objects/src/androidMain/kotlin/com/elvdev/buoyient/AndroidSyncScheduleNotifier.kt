package com.elvdev.buoyient.sync

import android.content.Context
import com.elvdev.buoyient.BuoyientPlatformContext
import com.elvdev.buoyient.SyncScheduler

public actual fun createPlatformSyncScheduleNotifier(): SyncScheduleNotifier =
    AndroidSyncScheduleNotifier(BuoyientPlatformContext.appContext)

public class AndroidSyncScheduleNotifier(private val context: Context) : SyncScheduleNotifier {
    override fun scheduleSyncIfNeeded() {
        SyncScheduler.scheduleSyncIfNeeded(context)
    }
}
