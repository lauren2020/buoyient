package com.elvdev.buoyient.globalconfigs

import io.ktor.client.HttpClient
import kotlin.concurrent.Volatile

/**
 * Process-wide [HttpClient] override. When set, [com.elvdev.buoyient.managers.ServerManager] uses this client
 * instead of creating a real Ktor HTTP client.
 *
 * Set via [Buoyient.httpClient] for mock mode or by [TestServiceEnvironment]
 * for integration tests.
 */
public object HttpClientOverride {
    @Volatile
    public var httpClient: HttpClient? = null
}
