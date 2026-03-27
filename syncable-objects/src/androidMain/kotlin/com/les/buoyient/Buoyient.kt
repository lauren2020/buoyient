package com.les.buoyient.globalconfigs

import android.content.Context
import com.les.buoyient.SyncServiceRegistryProvider
import com.les.buoyient.SyncWorker
import com.les.buoyient.SyncableObjectService

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
