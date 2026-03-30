package com.elvdev.buoyient.testing

import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Describes how a mock endpoint should fail when a [MockEndpointController]
 * override is active.
 *
 * Two modes model the real-world failure scenarios that apps need to handle:
 *
 * - [ServerError] — the server processes the request but returns an HTTP error.
 * - [Timeout] — the request times out, either before or after the server processes it.
 */
public sealed interface FailureOverride {

    /**
     * The real handler runs (server-side state mutates), but the response is replaced
     * with an HTTP error status code.
     *
     * @property statusCode the HTTP status code to return. Defaults to 500.
     */
    public data class ServerError(val statusCode: Int = 500) : FailureOverride

    /**
     * Simulates a request timeout.
     *
     * @property serverReceivedRequest controls whether the server-side handler runs
     *   before the timeout is thrown:
     *   - `true` — the real handler runs (server-side mutation takes effect), then
     *     [MockTimeoutException] is thrown. Simulates the case where the server
     *     processed the request but the response was lost in transit.
     *   - `false` — the real handler does **not** run. [MockConnectionException] is
     *     thrown (translated to [java.io.IOException] by the router). Simulates
     *     network-unreachable, DNS failure, or connection refused — the request
     *     never reached the server.
     */
    public data class Timeout(val serverReceivedRequest: Boolean = false) : FailureOverride
}

/**
 * A runtime-configurable controller that intercepts mock endpoint handlers to simulate
 * failures. Toggle overrides at any time — they take effect on the very next request.
 *
 * ## Usage with MockModeBuilder
 *
 * ```kotlin
 * val handle = MockModeBuilder()
 *     .service(MockNoteServer())
 *     .install()
 *
 * // Make the "create" endpoint return 503:
 * handle.endpointController.setOverride("notes", "create", FailureOverride.ServerError(503))
 *
 * // Make everything time out (request never reaches server):
 * handle.endpointController.setGlobalOverride(FailureOverride.Timeout())
 *
 * // Timeout where the server DID process the request (response lost in transit):
 * handle.endpointController.setOverride("notes", "create", FailureOverride.Timeout(serverReceivedRequest = true))
 *
 * // Back to normal:
 * handle.endpointController.clearAll()
 * ```
 *
 * ## Override resolution
 *
 * Per-endpoint overrides take precedence over the global override. If no per-endpoint
 * override is set, the global override (if any) applies.
 *
 * ## Thread safety
 *
 * All methods are safe to call from any thread. Override state is stored in a
 * [ConcurrentHashMap] and a [Volatile] field.
 */
public class MockEndpointController {

    private data class EndpointKey(val serviceName: String, val label: String)

    private val overrides = ConcurrentHashMap<EndpointKey, FailureOverride>()

    @Volatile
    private var globalOverride: FailureOverride? = null

    // -- Toggle API --

    /**
     * Sets a failure override for a single endpoint, identified by service name and label.
     *
     * @param serviceName the service's [MockServiceServer.name] (e.g. `"notes"`).
     * @param label the endpoint's [MockEndpoint.label] (e.g. `"create"`).
     * @param failure the failure mode to apply.
     */
    public fun setOverride(serviceName: String, label: String, failure: FailureOverride) {
        overrides[EndpointKey(serviceName, label)] = failure
    }

    /**
     * Sets a failure override for every endpoint in a service.
     *
     * @param serviceName the service name.
     * @param failure the failure mode to apply.
     * @param endpoints the endpoint list for this service (from [MockModeHandle.endpointIndex]).
     */
    public fun setServiceOverride(
        serviceName: String,
        failure: FailureOverride,
        endpoints: List<MockEndpoint>,
    ) {
        for (endpoint in endpoints) {
            overrides[EndpointKey(serviceName, endpoint.label)] = failure
        }
    }

    /**
     * Sets a global failure override that applies to all endpoints that don't have
     * a per-endpoint override.
     */
    public fun setGlobalOverride(failure: FailureOverride) {
        globalOverride = failure
    }

    /**
     * Clears the override for a single endpoint.
     */
    public fun clearOverride(serviceName: String, label: String) {
        overrides.remove(EndpointKey(serviceName, label))
    }

    /**
     * Clears all per-endpoint overrides for a service.
     */
    public fun clearServiceOverrides(serviceName: String, endpoints: List<MockEndpoint>) {
        for (endpoint in endpoints) {
            overrides.remove(EndpointKey(serviceName, endpoint.label))
        }
    }

    /**
     * Clears the global override.
     */
    public fun clearGlobalOverride() {
        globalOverride = null
    }

    /**
     * Clears every override — both per-endpoint and global.
     */
    public fun clearAll() {
        overrides.clear()
        globalOverride = null
    }

    /**
     * Returns the currently active override for an endpoint, or `null` if none.
     *
     * Resolution order: per-endpoint override first, then global override.
     */
    public fun activeOverride(serviceName: String, label: String): FailureOverride? =
        overrides[EndpointKey(serviceName, label)] ?: globalOverride

    // -- Wrapping API --

    /**
     * Wraps a [MockEndpoint]'s handler with failure-override interception.
     *
     * The returned handler checks [activeOverride] on every invocation, so toggles
     * set after wrapping take effect immediately.
     *
     * @param serviceName the service name this endpoint belongs to.
     * @param endpoint the endpoint whose handler to wrap.
     * @return a [MockRequestHandler] that delegates to the real handler unless an
     *   override is active.
     */
    public fun wrap(serviceName: String, endpoint: MockEndpoint): MockRequestHandler {
        val realHandler = endpoint.handler
        return MockRequestHandler { request ->
            when (val override = activeOverride(serviceName, endpoint.label)) {
                null -> {
                    realHandler.handle(request)
                }
                is FailureOverride.ServerError -> {
                    realHandler.handle(request)
                    MockResponse(statusCode = override.statusCode, body = JsonObject(emptyMap()))
                }
                is FailureOverride.Timeout -> if (override.serverReceivedRequest) {
                    realHandler.handle(request)
                    throw MockTimeoutException("Simulated timeout (post-server) via MockEndpointController")
                } else {
                    throw MockConnectionException("Simulated timeout (pre-server) via MockEndpointController")
                }
            }
        }
    }
}
