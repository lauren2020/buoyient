package com.example.sync

import android.content.Context

/**
 * Provides [SyncableObjectService] instances for the [SyncWorker] to sync.
 *
 * The app module must implement this interface and register it via
 * [SyncWorker.registerServiceProvider] so the SDK knows which services
 * to sync in the background.
 */
interface SyncServiceRegistryProvider {
    fun createServices(context: Context): List<SyncableObjectService<*>>
}
