package com.les.buoyient

import android.content.Context
import com.les.buoyient.sync.SyncDriver

/**
 * Provides [com.les.buoyient.sync.SyncDriver] instances for the [SyncWorker] to sync.
 *
 * The app module must implement this interface and register it via
 * [SyncWorker.registerServiceProvider] so the SDK knows which services
 * to sync in the background.
 */
public interface SyncServiceRegistryProvider {
    public fun createDrivers(context: Context): List<SyncDriver<*, *>>
}
