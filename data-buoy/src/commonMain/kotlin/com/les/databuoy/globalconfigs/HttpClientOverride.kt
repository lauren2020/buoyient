package com.les.databuoy.globalconfigs

import io.ktor.client.HttpClient

/**
 * Process-wide [HttpClient] override. When set, [com.les.databuoy.internalutilities.ServerManager] uses this client
 * instead of creating a real Ktor HTTP client.
 *
 * Set via [DataBuoy.httpClient] for mock mode or by [TestServiceEnvironment]
 * for integration tests.
 */
public object HttpClientOverride {
    @Volatile
    public var httpClient: HttpClient? = null
}
