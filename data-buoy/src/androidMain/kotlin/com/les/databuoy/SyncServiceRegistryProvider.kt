package com.les.databuoy

import android.content.Context

/**
 * Provides [SyncDriver] instances for the [SyncWorker] to sync.
 *
 * The app module must implement this interface and register it via
 * [SyncWorker.registerServiceProvider] so the SDK knows which services
 * to sync in the background.
 */
public interface SyncServiceRegistryProvider {
    public fun createDrivers(context: Context): List<SyncDriver<*, *>>
}
