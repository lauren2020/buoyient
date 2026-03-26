package com.les.databuoy

import android.content.Context

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
 * DataBuoy.registerServiceProvider(object : SyncServiceRegistryProvider {
 *     override fun createDrivers(context: Context) = listOf(
 *         CommentService().syncDriver,
 *         PostService().syncDriver,
 *     )
 * })
 * ```
 */
fun DataBuoy.registerServiceProvider(provider: SyncServiceRegistryProvider) {
    SyncWorker.registerServiceProvider(provider)
}
