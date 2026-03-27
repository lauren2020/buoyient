package com.les.databuoy.globalconfigs

import android.content.Context
import com.les.databuoy.SyncServiceRegistryProvider
import com.les.databuoy.SyncWorker
import com.les.databuoy.SyncableObjectService

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
public fun DataBuoy.registerServiceProvider(provider: SyncServiceRegistryProvider) {
    SyncWorker.registerServiceProvider(provider)
}
