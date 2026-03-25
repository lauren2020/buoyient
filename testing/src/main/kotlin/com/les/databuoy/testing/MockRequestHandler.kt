package com.les.databuoy.testing

import com.les.databuoy.HttpRequest
import kotlinx.serialization.json.JsonObject

/**
 * A recorded HTTP request captured by [MockEndpointRouter] for later inspection.
 */
data class RecordedRequest(
    val method: HttpRequest.HttpMethod,
    val url: String,
    val body: JsonObject,
    val headers: Map<String, String>,
)

/**
 * The response that a [MockRequestHandler] returns to simulate a server response.
 *
 * @property statusCode the HTTP status code to return.
 * @property body the JSON response body.
 * @property epochTimestamp the response timestamp in epoch seconds. Defaults to the current time.
 */
data class MockResponse(
    val statusCode: Int,
    val body: JsonObject,
    val epochTimestamp: Long = System.currentTimeMillis() / 1000,
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
fun interface MockRequestHandler {
    fun handle(request: RecordedRequest): MockResponse
}

/**
 * Throw this from a [MockRequestHandler] to simulate a network connection error.
 * The [MockEndpointRouter] will translate this into a
 * [ServerManager.ServerManagerResponse.ConnectionError].
 */
class MockConnectionException(
    message: String = "Simulated connection error",
) : Exception(message)

/**
 * Throw this from a [MockRequestHandler] to simulate a request timeout.
 * The [MockEndpointRouter] will translate this into a
 * [ServerManager.ServerManagerResponse.RequestTimedOut].
 */
class MockTimeoutException(
    message: String = "Simulated request timeout",
) : Exception(message)
