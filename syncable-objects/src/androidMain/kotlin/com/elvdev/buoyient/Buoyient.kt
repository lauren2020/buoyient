package com.elvdev.buoyient.globalconfigs

import android.content.Context
import com.elvdev.buoyient.SyncServiceRegistryProvider
import com.elvdev.buoyient.SyncWorker
import com.elvdev.buoyient.SyncableObjectService

internal actual fun platformRegisterServices(services: Set<SyncableObjectService<*, *>>) {
    val drivers = services.map { it.syncDriver }
    SyncWorker.registerServiceProvider(object : SyncServiceRegistryProvider {
        override fun createDrivers(context: Context) = drivers
    })
}

/**
 * Register a [SyncServiceRegistryProvider] that creates drivers on demand.
 *
 * The provider's [SyncServiceRegistryProvider.createDrivers] is called each
 * time [SyncWorker] runs, so it can create fresh instances per sync pass.
 *
 * ```kotlin
 * Buoyient.registerServiceProvider(object : SyncServiceRegistryProvider {
 *     override fun createDrivers(context: Context) = listOf(
 *         CommentService().syncDriver,
 *         PostService().syncDriver,
 *     )
 * })
 * ```
 */
public fun Buoyient.registerServiceProvider(provider: SyncServiceRegistryProvider) {
    SyncWorker.registerServiceProvider(provider)
}
