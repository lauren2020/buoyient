package com.elvdev.buoyient.testing

import com.elvdev.buoyient.datatypes.HttpRequest

/**
 * Declares a single mock HTTP endpoint with its method, URL pattern, label, and handler.
 *
 * Endpoints are returned by [MockServiceServer.endpoints] and registered with a
 * [MockEndpointRouter] during [MockModeBuilder.install]. The [label] provides a
 * human-readable identifier scoped to its service (e.g. `"create"`, `"list"`,
 * `"sync-down"`), enabling programmatic access to specific endpoints.
 *
 * @property method the HTTP method this endpoint responds to.
 * @property urlPattern the URL pattern (exact or wildcard) to match against.
 * @property label a short identifier for this endpoint, unique within its service
 *   (e.g. `"create"`, `"update"`, `"get"`, `"list"`, `"void"`, `"sync-down"`).
 * @property handler the handler that produces a [MockResponse] for matching requests.
 */
public data class MockEndpoint(
    public val method: HttpRequest.HttpMethod,
    public val urlPattern: String,
    public val label: String,
    public val handler: MockRequestHandler,
)
