package com.example.sync

import android.content.Context

/**
 * Main entry point for configuring data-buoy on Android.
 *
 * Consumers can register their [SyncableObjectService] instances via
 * [registerServices] (for pre-constructed instances) or
 * [registerServiceProvider] (for lazy/factory-based creation).
 *
 * If using the `data-buoy-hilt` artifact, registration is handled
 * automatically — just provide your services via Hilt's `@IntoSet` multibinding.
 */
object DataBuoy {

    /**
     * Register a fixed set of already-constructed services.
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
        SyncWorker.registerServiceProvider(object : SyncServiceRegistryProvider {
            override fun createServices(context: Context) = services.toList()
        })
    }

    /**
     * Register a [SyncServiceRegistryProvider] that creates services on demand.
     *
     * The provider's [SyncServiceRegistryProvider.createServices] is called each
     * time [SyncWorker] runs, so it can create fresh service instances per sync
     * pass. This is the preferred approach when services hold resources that
     * should not outlive a single sync cycle.
     *
     * ```kotlin
     * DataBuoy.registerServiceProvider(object : SyncServiceRegistryProvider {
     *     override fun createServices(context: Context) = listOf(
     *         CommentService(context),
     *         PostService(context),
     *     )
     * })
     * ```
     */
    fun registerServiceProvider(provider: SyncServiceRegistryProvider) {
        SyncWorker.registerServiceProvider(provider)
    }
}
