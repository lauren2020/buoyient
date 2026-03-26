package com.les.databuoy

/**
 * Provides headers that should be applied to every HTTP request made by data-buoy,
 * across all services.
 *
 * Evaluated at request time, so implementations can return fresh auth tokens without
 * needing to update the provider itself.
 *
 * Register a provider via [DataBuoy.globalHeaderProvider] at app startup:
 *
 * ```kotlin
 * DataBuoy.globalHeaderProvider = GlobalHeaderProvider {
 *     listOf("Authorization" to "Bearer ${authRepository.currentAccessToken}")
 * }
 * ```
 */
public fun interface GlobalHeaderProvider {
    public fun headers(): List<Pair<String, String>>
}

/**
 * Process-wide holder for the [GlobalHeaderProvider].
 *
 * This is the common (KMP) backing store. Platform-specific [DataBuoy] objects
 * delegate to this registry.
 */
public object GlobalHeaderProviderRegistry {
    public var provider: GlobalHeaderProvider? = null
}
