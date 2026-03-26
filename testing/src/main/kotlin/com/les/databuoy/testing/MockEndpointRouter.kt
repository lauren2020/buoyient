package com.les.databuoy.testing

import com.les.databuoy.syncableobjectservicedatatypes.HttpRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.CopyOnWriteArrayList

// A mock HTTP server that routes requests to registered MockRequestHandlers based on
// HTTP method and URL pattern. Use this to test SyncableObjectService implementations
// without making real network calls.
//
// URL Pattern Matching:
//  - Exact match: "https://api.example.com/items" matches only that URL.
//  - Trailing wildcard: "https://api.example.com/items/*" matches any URL
//    that starts with "https://api.example.com/items/".
//  - Leading wildcard: "*/items" matches any URL ending with "/items".
//  - Both wildcards: "*/items/*" matches any URL containing "/items/".
//
// Routes are matched in registration order. The first matching handler wins.
// Unmatched requests return a 404 with an empty JSON body.
public class MockEndpointRouter {

    private data class Route(
        val method: HttpRequest.HttpMethod,
        val urlPattern: String,
        val handler: MockRequestHandler,
    )

    private val routes = CopyOnWriteArrayList<Route>()
    private val _requestLog = CopyOnWriteArrayList<RecordedRequest>()

    /** All requests that have been received, in order. */
    public val requestLog: List<RecordedRequest> get() = _requestLog.toList()

    /** Clears the recorded request log. */
    public fun clearRequestLog() {
        _requestLog.clear()
    }

    // -- Registration API --

    /**
     * Registers a handler for requests matching [method] and [urlPattern].
     * Returns `this` for chaining.
     */
    public fun on(
        method: HttpRequest.HttpMethod,
        urlPattern: String,
        handler: MockRequestHandler,
    ): MockEndpointRouter {
        routes.add(Route(method, urlPattern, handler))
        return this
    }

    /** Registers a handler that always returns a static response. */
    public fun on(
        method: HttpRequest.HttpMethod,
        urlPattern: String,
        statusCode: Int,
        responseBody: JsonObject,
    ): MockEndpointRouter = on(method, urlPattern) { MockResponse(statusCode, responseBody) }

    public fun onGet(urlPattern: String, handler: MockRequestHandler): MockEndpointRouter =
        on(HttpRequest.HttpMethod.GET, urlPattern, handler)

    public fun onPost(urlPattern: String, handler: MockRequestHandler): MockEndpointRouter =
        on(HttpRequest.HttpMethod.POST, urlPattern, handler)

    public fun onPut(urlPattern: String, handler: MockRequestHandler): MockEndpointRouter =
        on(HttpRequest.HttpMethod.PUT, urlPattern, handler)

    public fun onPatch(urlPattern: String, handler: MockRequestHandler): MockEndpointRouter =
        on(HttpRequest.HttpMethod.PATCH, urlPattern, handler)

    public fun onDelete(urlPattern: String, handler: MockRequestHandler): MockEndpointRouter =
        on(HttpRequest.HttpMethod.DELETE, urlPattern, handler)

    // -- Build outputs --

    /**
     * Creates a Ktor [HttpClient] backed by a [MockEngine] that routes requests
     * through this router's registered handlers.
     */
    public fun buildHttpClient(): HttpClient {
        val engine = MockEngine { requestData ->
            val method = requestData.method.value.uppercase().let { methodValue ->
                HttpRequest.HttpMethod.entries.find { it.value == methodValue }
                    ?: HttpRequest.HttpMethod.GET
            }
            val url = requestData.url.toString()
            val body = try {
                val bodyBytes = (requestData.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)
                    ?.bytes()
                    ?.decodeToString()
                if (bodyBytes.isNullOrBlank()) {
                    JsonObject(emptyMap())
                } else {
                    Json.parseToJsonElement(bodyBytes).jsonObject
                }
            } catch (e: Exception) {
                System.err.println("MockEndpointRouter: failed to parse request body as JSON: ${e.message}")
                JsonObject(emptyMap())
            }
            val headers = buildMap {
                requestData.headers.forEach { name, values ->
                    put(name, values.joinToString(", "))
                }
            }

            val recorded = RecordedRequest(method, url, body, headers)
            _requestLog.add(recorded)

            val route = findRoute(method, url)
            if (route == null) {
                respond(
                    content = "{}",
                    status = HttpStatusCode.NotFound,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                try {
                    val mockResponse = route.handler.handle(recorded)
                    respond(
                        content = ByteReadChannel(mockResponse.body.toString().toByteArray()),
                        status = HttpStatusCode.fromValue(mockResponse.statusCode),
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } catch (_: MockConnectionException) {
                    throw java.io.IOException("Simulated connection error")
                } catch (_: MockTimeoutException) {
                    throw io.ktor.client.plugins.HttpRequestTimeoutException(
                        url = recorded.url,
                        timeoutMillis = 30_000,
                    )
                }
            }
        }
        return HttpClient(engine)
    }

    // -- Internal --

    private fun findRoute(method: HttpRequest.HttpMethod, url: String): Route? =
        routes.firstOrNull { it.method == method && matchesPattern(it.urlPattern, url) }

    public companion object {
        internal fun matchesPattern(pattern: String, url: String): Boolean {
            val startsWithWild = pattern.startsWith("*")
            val endsWithWild = pattern.endsWith("*")

            return when {
                startsWithWild && endsWithWild -> {
                    val core = pattern.removeSurrounding("*")
                    url.contains(core)
                }
                endsWithWild -> {
                    val prefix = pattern.removeSuffix("*")
                    url.startsWith(prefix)
                }
                startsWithWild -> {
                    val suffix = pattern.removePrefix("*")
                    url.endsWith(suffix)
                }
                else -> url == pattern
            }
        }
    }
}
