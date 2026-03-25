package com.les.databuoy

/**
 * Main entry point for configuring data-buoy on iOS.
 *
 * Call [registerServices] once at app startup to provide the set of
 * [SyncableObjectService] instances that should participate in background sync.
 *
 * ```swift
 * // In AppDelegate.swift:
 * DataBuoy.shared.registerServices(services: [commentService, postService])
 * IosSyncScheduleNotifier.Companion.shared.registerHandler()
 * ```
 *
 * For on-demand sync, call [syncNow].
 *
 * **AI agents:** See `CLAUDE.md` at the repository root (or `META-INF/CLAUDE.md` in the
 * published JAR) and the `docs/` directory for integration guides, key class reference,
 * and conventions.
 */
object DataBuoy {

    val status: DataBuoyStatus
        get() = DataBuoyStatus.shared

    /**
     * A [GlobalHeaderProvider] whose [GlobalHeaderProvider.headers] are applied to every
     * HTTP request made by data-buoy, across all services. Set this once at app startup.
     *
     * The provider is evaluated on every request, so refreshed tokens are picked up
     * automatically — you never need to update this property after setting it.
     */
    var globalHeaderProvider: GlobalHeaderProvider?
        get() = GlobalHeaderProviderRegistry.provider
        set(value) { GlobalHeaderProviderRegistry.provider = value }

    internal val registeredServices = mutableSetOf<SyncableObjectService<*, *>>()

    /**
     * Register a set of already-constructed services for background sync.
     *
     * Unlike Android, iOS services are long-lived (no WorkManager context
     * recreation), so the same instances are reused across sync passes.
     */
    fun registerServices(services: Set<SyncableObjectService<*, *>>) {
        registeredServices.clear()
        registeredServices.addAll(services)
    }

    /**
     * Trigger an immediate sync-up pass (e.g. when the app returns to
     * foreground or after a batch of offline writes).
     *
     * @param completion called with `true` when the queue is fully drained
     *   (or only blocked by conflicts), `false` if requests remain.
     */
    fun syncNow(completion: (Boolean) -> Unit = {}) {
        IosSyncRunner().performSync(completion)
    }
}
