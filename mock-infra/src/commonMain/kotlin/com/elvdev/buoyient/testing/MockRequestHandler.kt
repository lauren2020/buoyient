package com.elvdev.buoyient.testing

import com.elvdev.buoyient.datatypes.HttpRequest
import kotlinx.serialization.json.JsonObject

/**
 * A recorded HTTP request captured by [MockEndpointRouter] for later inspection.
 */
public data class RecordedRequest(
    public val method: HttpRequest.HttpMethod,
    public val url: String,
    public val body: JsonObject,
    public val headers: Map<String, String>,
)

/**
 * The response that a [MockRequestHandler] returns to simulate a server response.
 *
 * @property statusCode the HTTP status code to return.
 * @property body the JSON response body.
 * @property epochTimestamp the response timestamp in epoch seconds. Defaults to the current time.
 */
public data class MockResponse(
    public val statusCode: Int,
    public val body: JsonObject,
    public val epochTimestamp: Long = kotlinx.datetime.Clock.System.now().epochSeconds,
)

/**
 * Handler that processes a [RecordedRequest] and returns a [MockResponse].
 *
 * Register handlers on [MockEndpointRouter] to define how mock endpoints respond:
 * ```kotlin
 * router.onPost("https://api.example.com/items") { request ->
 *     MockResponse(statusCode = 201, body = buildJsonObject { ... })
 * }
 * ```
 */
public fun interface MockRequestHandler {
    public fun handle(request: RecordedRequest): MockResponse
}

/**
 * Throw this from a [MockRequestHandler] to simulate a network connection error.
 * The [MockEndpointRouter] will translate this into a connection error exception.
 */
public class MockConnectionException(
    message: String = "Simulated connection error",
) : Exception(message)

/**
 * Throw this from a [MockRequestHandler] to simulate a request timeout.
 * The [MockEndpointRouter] will translate this into an
 * [io.ktor.client.plugins.HttpRequestTimeoutException].
 */
public class MockTimeoutException(
    message: String = "Simulated request timeout",
) : Exception(message)
