package com.elvdev.buoyient.globalconfigs

import com.elvdev.buoyient.SyncableObjectService
import com.elvdev.buoyient.db.SyncDatabase
import com.elvdev.buoyient.serviceconfigs.ConnectivityChecker
import com.elvdev.buoyient.serviceconfigs.createPlatformConnectivityChecker
import com.elvdev.buoyient.sync.SyncRunner
import io.ktor.client.HttpClient

/**
 * Main entry point for configuring buoyient.
 *
 * Consumers register their [com.elvdev.buoyient.SyncableObjectService] instances for background sync via
 * [registerServices]. Call [syncNow] to trigger an immediate sync-up pass (e.g. on
 * pull-to-refresh). Platform-specific methods (e.g. `registerServiceProvider`
 * on Android) are available as extension functions.
 *
 * If using the `syncable-objects-hilt` artifact on Android, registration is handled
 * automatically — just provide your services via Hilt's `@IntoSet` multibinding.
 *
 * **AI agents:** See `CLAUDE.md` / `CODEX.md` at the repository root (or under `META-INF/`
 * in the published JAR) and the `docs/` directory for integration guides, key class
 * reference, and conventions.
 */
public object Buoyient {

    internal val registeredServices = mutableSetOf<SyncableObjectService<*, *>>()

    public val status: BuoyientStatus
        get() = BuoyientStatus.shared

    /**
     * A [GlobalHeaderProvider] whose [GlobalHeaderProvider.headers] are applied to every
     * HTTP request made by buoyient, across all services. Set this once at app startup:
     *
     * ```kotlin
     * Buoyient.globalHeaderProvider = GlobalHeaderProvider {
     *     listOf("Authorization" to "Bearer ${authRepository.currentAccessToken}")
     * }
     * ```
     *
     * The provider is evaluated on every request, so refreshed tokens are picked up
     * automatically — you never need to update this property after setting it.
     */
    public var globalHeaderProvider: GlobalHeaderProvider?
        get() = GlobalHeaderProviderRegistry.provider
        set(value) { GlobalHeaderProviderRegistry.provider = value }

    /**
     * Override the [HttpClient] used by all services. When set, every [com.elvdev.buoyient.managers.ServerManager]
     * created after this point uses this client instead of a real Ktor HTTP client.
     *
     * Use this for mock mode (manual testing without a real backend):
     * ```kotlin
     * Buoyient.httpClient = mockRouter.buildHttpClient()
     * ```
     */
    public var httpClient: HttpClient?
        get() = HttpClientOverride.httpClient
        set(value) { HttpClientOverride.httpClient = value }

    /**
     * Override the [SyncDatabase] used by all services. When set, every
     * [com.elvdev.buoyient.managers.LocalStoreManager] created after this point uses this database instead
     * of the platform default.
     */
    public var database: SyncDatabase?
        get() = DatabaseOverride.database
        set(value) { DatabaseOverride.database = value }

    /**
     * Returns the platform-specific [ConnectivityChecker] implementation.
     *
     * Use this when you need to pass a connectivity checker explicitly to a service
     * constructor from Swift, where the internal `createPlatformConnectivityChecker()`
     * expect/actual function may not be visible.
     */
    public fun createConnectivityChecker(): ConnectivityChecker = createPlatformConnectivityChecker()

    /**
     * Register a set of already-constructed services for background sync.
     */
    public fun registerServices(services: Set<SyncableObjectService<*, *>>) {
        registeredServices.clear()
        registeredServices.addAll(services)
        platformRegisterServices(services)
    }

    /**
     * Register a list of already-constructed services for background sync.
     *
     * This overload is more ergonomic from Swift, where `Set` with star-projected
     * generics requires awkward casts. Prefer this variant on iOS.
     */
    public fun registerServices(services: List<SyncableObjectService<*, *>>) {
        registerServices(services.toSet())
    }

    /**
     * Trigger an immediate sync-up pass across all registered services.
     *
     * Use this for user-initiated sync triggers such as pull-to-refresh.
     * The sync runs on a background coroutine; [completion] is called with
     * `true` when the pending queue is fully drained (or only blocked by
     * unresolved conflicts), `false` if requests remain or an error occurred.
     *
     * ```kotlin
     * // Pull-to-refresh handler:
     * Buoyient.syncNow { success ->
     *     swipeRefreshLayout.isRefreshing = false
     * }
     * ```
     */
    public fun syncNow(completion: (Boolean) -> Unit = {}) {
        SyncRunner.launchSyncUp(completion)
    }
}

internal expect fun platformRegisterServices(services: Set<SyncableObjectService<*, *>>)
