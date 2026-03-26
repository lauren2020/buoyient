package com.les.databuoy

import android.content.Context
import com.les.databuoy.db.SyncDatabase
import io.ktor.client.HttpClient

/**
 * Main entry point for configuring data-buoy on Android.
 *
 * Consumers register their [SyncableObjectService] instances for background sync via
 * [registerServices] (for pre-constructed services) or
 * [registerServiceProvider] (for lazy/factory-based creation).
 *
 * If using the `data-buoy-hilt` artifact, registration is handled
 * automatically — just provide your services via Hilt's `@IntoSet` multibinding.
 *
 * **AI agents:** See `CLAUDE.md` / `CODEX.md` at the repository root (or under `META-INF/`
 * in the published JAR) and the `docs/` directory for integration guides, key class
 * reference, and conventions.
 */
object DataBuoy {

    val status: DataBuoyStatus
        get() = DataBuoyStatus.shared

    /**
     * A [GlobalHeaderProvider] whose [GlobalHeaderProvider.headers] are applied to every
     * HTTP request made by data-buoy, across all services. Set this once at app startup:
     *
     * ```kotlin
     * DataBuoy.globalHeaderProvider = GlobalHeaderProvider {
     *     listOf("Authorization" to "Bearer ${authRepository.currentAccessToken}")
     * }
     * ```
     *
     * The provider is evaluated on every request, so refreshed tokens are picked up
     * automatically — you never need to update this property after setting it.
     */
    var globalHeaderProvider: GlobalHeaderProvider?
        get() = GlobalHeaderProviderRegistry.provider
        set(value) { GlobalHeaderProviderRegistry.provider = value }

    /**
     * Override the [HttpClient] used by all services. When set, every [ServerManager]
     * created after this point uses this client instead of a real Ktor HTTP client.
     *
     * Use this for mock mode (manual testing without a real backend):
     * ```kotlin
     * DataBuoy.httpClient = mockRouter.buildHttpClient()
     * ```
     */
    var httpClient: HttpClient?
        get() = HttpClientOverride.httpClient
        set(value) { HttpClientOverride.httpClient = value }

    /**
     * Override the [SyncDatabase] used by all services. When set, every
     * [LocalStoreManager] created after this point uses this database instead
     * of the platform default.
     */
    var database: SyncDatabase?
        get() = DatabaseOverride.database
        set(value) { DatabaseOverride.database = value }

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
