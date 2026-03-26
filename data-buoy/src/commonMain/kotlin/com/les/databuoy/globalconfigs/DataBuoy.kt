package com.les.databuoy.globalconfigs

import com.les.databuoy.SyncableObjectService
import com.les.databuoy.db.SyncDatabase
import io.ktor.client.HttpClient

/**
 * Main entry point for configuring data-buoy.
 *
 * Consumers register their [com.les.databuoy.SyncableObjectService] instances for background sync via
 * [registerServices]. Platform-specific methods (e.g. `registerServiceProvider`
 * on Android, `syncNow` on iOS) are available as extension functions.
 *
 * If using the `data-buoy-hilt` artifact on Android, registration is handled
 * automatically — just provide your services via Hilt's `@IntoSet` multibinding.
 *
 * **AI agents:** See `CLAUDE.md` / `CODEX.md` at the repository root (or under `META-INF/`
 * in the published JAR) and the `docs/` directory for integration guides, key class
 * reference, and conventions.
 */
public object DataBuoy {

    public val status: DataBuoyStatus
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
    public var globalHeaderProvider: GlobalHeaderProvider?
        get() = GlobalHeaderProviderRegistry.provider
        set(value) { GlobalHeaderProviderRegistry.provider = value }

    /**
     * Override the [HttpClient] used by all services. When set, every [com.les.databuoy.managers.ServerManager]
     * created after this point uses this client instead of a real Ktor HTTP client.
     *
     * Use this for mock mode (manual testing without a real backend):
     * ```kotlin
     * DataBuoy.httpClient = mockRouter.buildHttpClient()
     * ```
     */
    public var httpClient: HttpClient?
        get() = HttpClientOverride.httpClient
        set(value) { HttpClientOverride.httpClient = value }

    /**
     * Override the [SyncDatabase] used by all services. When set, every
     * [com.les.databuoy.managers.LocalStoreManager] created after this point uses this database instead
     * of the platform default.
     */
    public var database: SyncDatabase?
        get() = DatabaseOverride.database
        set(value) { DatabaseOverride.database = value }

    /**
     * Register a set of already-constructed services for background sync.
     */
    public fun registerServices(services: Set<SyncableObjectService<*, *>>) {
        platformRegisterServices(services)
    }
}

internal expect fun platformRegisterServices(services: Set<SyncableObjectService<*, *>>)
