package com.les.databuoy

import android.content.Context
import androidx.startup.Initializer
import androidx.work.WorkManagerInitializer

/**
 * Auto-initializer registered via `androidx.startup`.
 *
 * Captures the application [Context] so that platform-specific implementations
 * (e.g., [AndroidSyncScheduleNotifier], [AndroidConnectivityChecker]) can access
 * it without consumers having to pass it explicitly.
 */
public class DataBuoyInitializer : Initializer<Unit> {

    override fun create(context: Context) {
        DataBuoyPlatformContext.initialize(context)
        // Trigger sync for any data queued from a previous session
        SyncScheduler.scheduleSyncIfNeeded(DataBuoyPlatformContext.appContext)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> =
        listOf(WorkManagerInitializer::class.java)
}
