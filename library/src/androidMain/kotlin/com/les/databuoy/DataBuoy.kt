package com.les.databuoy

import android.content.Context

/**
 * Main entry point for configuring data-buoy on Android.
 *
 * Consumers register their [SyncableObjectService] instances for background sync via
 * [registerServices] (for pre-constructed services) or
 * [registerServiceProvider] (for lazy/factory-based creation).
 *
 * If using the `data-buoy-hilt` artifact, registration is handled
 * automatically — just provide your services via Hilt's `@IntoSet` multibinding.
 */
object DataBuoy {

    val status: DataBuoyStatus
        get() = DataBuoyStatus.shared

    /**
     * Register a fixed set of services for background sync.
     *
     * Convenient when services are created eagerly (e.g. in `Application.onCreate()`
     * or via dependency injection).
     *
     * ```kotlin
     * // In Application.onCreate()
     * DataBuoy.registerServices(setOf(commentService, postService))
     * ```
     */
    fun registerServices(services: Set<SyncableObjectService<*, *>>) {
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
    fun registerServiceProvider(provider: SyncServiceRegistryProvider) {
        SyncWorker.registerServiceProvider(provider)
    }
}
