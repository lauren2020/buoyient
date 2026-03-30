package com.elvdev.buoyient.testing

import com.elvdev.buoyient.datatypes.HttpRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Functions that produce [MockEndpoint] lists backed by a [MockServerCollection],
 * plus convenience extension functions that register them directly on a
 * [MockEndpointRouter].
 *
 * ## Quick start
 *
 * ```kotlin
 * val store = MockServerStore()
 * val todos = store.collection("todos")
 *
 * // Declarative — get a list of MockEndpoint objects:
 * val endpoints = crudEndpoints(
 *     collection = todos,
 *     baseUrl = "https://api.example.com/todos",
 * )
 *
 * // Or register directly on a router:
 * val router = MockEndpointRouter()
 * router.registerCrudHandlers(
 *     collection = todos,
 *     baseUrl = "https://api.example.com/todos",
 * )
 * ```
 *
 * The [responseWrapper] and [listResponseWrapper] parameters control the JSON
 * envelope shape. Override them to match your API's actual response format.
 */

/**
 * Returns a list of [MockEndpoint]s for standard CRUD operations backed by [collection]:
 *
 * - `create` — POST `baseUrl` → [MockServerCollection.create] → 201
 * - `update` — PUT `baseUrl/{id}` → [MockServerCollection.update] → 200 or 404
 * - `get` — GET `baseUrl/{id}` → [MockServerCollection.get] → 200 or 404
 * - `list` — GET `baseUrl` → [MockServerCollection.getAll] → 200
 * - `void` — DELETE `baseUrl/{id}` → [MockServerCollection.void] → 200 or 404
 *
 * @param collection the server-side collection to back the handlers.
 * @param baseUrl the base URL for the resource (e.g. `"https://api.example.com/todos"`).
 *   Individual resource URLs are `baseUrl/{serverId}`.
 * @param responseWrapper transforms a single [MockServerRecord] into the JSON response body.
 *   Defaults to `{"data": <record>}`.
 * @param listResponseWrapper transforms a list of records into the JSON response body.
 *   Defaults to `{"data": [<records>]}`.
 */
public fun crudEndpoints(
    collection: MockServerCollection,
    baseUrl: String,
    responseWrapper: (MockServerRecord) -> JsonObject = ::defaultSingleWrapper,
    listResponseWrapper: (List<MockServerRecord>) -> JsonObject = ::defaultListWrapper,
): List<MockEndpoint> = listOf(
    MockEndpoint(
        method = HttpRequest.HttpMethod.POST,
        urlPattern = baseUrl,
        label = "create",
    ) { request ->
        val record = collection.create(request.body)
        MockResponse(statusCode = 201, body = responseWrapper(record))
    },
    MockEndpoint(
        method = HttpRequest.HttpMethod.PUT,
        urlPattern = "$baseUrl/*",
        label = "update",
    ) { request ->
        val serverId = extractTrailingSegment(request.url, baseUrl)
        val record = collection.update(serverId, request.body)
        if (record != null) {
            MockResponse(statusCode = 200, body = responseWrapper(record))
        } else {
            MockResponse(statusCode = 404, body = JsonObject(emptyMap()))
        }
    },
    // GET baseUrl/* registered before GET baseUrl because MockEndpointRouter uses
    // first-match routing, and the trailing-wildcard pattern is more specific.
    MockEndpoint(
        method = HttpRequest.HttpMethod.GET,
        urlPattern = "$baseUrl/*",
        label = "get",
    ) { request ->
        val serverId = extractTrailingSegment(request.url, baseUrl)
        val record = collection.get(serverId)
        if (record != null) {
            MockResponse(statusCode = 200, body = responseWrapper(record))
        } else {
            MockResponse(statusCode = 404, body = JsonObject(emptyMap()))
        }
    },
    MockEndpoint(
        method = HttpRequest.HttpMethod.GET,
        urlPattern = baseUrl,
        label = "list",
    ) { _ ->
        MockResponse(statusCode = 200, body = listResponseWrapper(collection.getAll()))
    },
    MockEndpoint(
        method = HttpRequest.HttpMethod.DELETE,
        urlPattern = "$baseUrl/*",
        label = "void",
    ) { request ->
        val serverId = extractTrailingSegment(request.url, baseUrl)
        val record = collection.void(serverId)
        if (record != null) {
            MockResponse(statusCode = 200, body = responseWrapper(record))
        } else {
            MockResponse(statusCode = 404, body = JsonObject(emptyMap()))
        }
    },
)

/**
 * Returns a single [MockEndpoint] for a POST-based sync-down handler backed by [collection].
 *
 * This matches [SyncFetchConfig.PostFetchConfig] where the client sends a POST
 * with a body containing the last sync timestamp, and the server returns records
 * updated since that timestamp.
 *
 * @param collection the server-side collection to query.
 * @param urlPattern the URL pattern for the sync-down endpoint.
 * @param listResponseWrapper transforms the list of records into the JSON response body.
 * @param extractTimestamp extracts the `last_synced_timestamp` (epoch seconds) from the
 *   request body. Return `null` to get all records instead of a delta.
 */
public fun syncDownEndpoint(
    collection: MockServerCollection,
    urlPattern: String,
    listResponseWrapper: (List<MockServerRecord>) -> JsonObject = ::defaultListWrapper,
    extractTimestamp: (RecordedRequest) -> Long? = { null },
): MockEndpoint = MockEndpoint(
    method = HttpRequest.HttpMethod.POST,
    urlPattern = urlPattern,
    label = "sync-down",
) { request ->
    val since = extractTimestamp(request)
    val records = if (since != null) {
        collection.getUpdatedSince(since)
    } else {
        collection.getAll()
    }
    MockResponse(statusCode = 200, body = listResponseWrapper(records))
}

// -- Router convenience extensions (delegate to the endpoint-list functions) --

/**
 * Registers standard CRUD handlers on this router, backed by [collection].
 *
 * This is a convenience that calls [crudEndpoints] and registers each endpoint.
 * Prefer [crudEndpoints] directly when using [MockServiceServer.endpoints].
 *
 * @return this router, for chaining.
 * @see crudEndpoints
 */
public fun MockEndpointRouter.registerCrudHandlers(
    collection: MockServerCollection,
    baseUrl: String,
    responseWrapper: (MockServerRecord) -> JsonObject = ::defaultSingleWrapper,
    listResponseWrapper: (List<MockServerRecord>) -> JsonObject = ::defaultListWrapper,
): MockEndpointRouter {
    for (endpoint in crudEndpoints(collection, baseUrl, responseWrapper, listResponseWrapper)) {
        on(endpoint.method, endpoint.urlPattern, endpoint.handler)
    }
    return this
}

/**
 * Registers a POST-based sync-down handler on this router, backed by [collection].
 *
 * This is a convenience that calls [syncDownEndpoint] and registers it.
 * Prefer [syncDownEndpoint] directly when using [MockServiceServer.endpoints].
 *
 * @return this router, for chaining.
 * @see syncDownEndpoint
 */
public fun MockEndpointRouter.registerSyncDownHandler(
    collection: MockServerCollection,
    urlPattern: String,
    listResponseWrapper: (List<MockServerRecord>) -> JsonObject = ::defaultListWrapper,
    extractTimestamp: (RecordedRequest) -> Long? = { null },
): MockEndpointRouter {
    val endpoint = syncDownEndpoint(collection, urlPattern, listResponseWrapper, extractTimestamp)
    on(endpoint.method, endpoint.urlPattern, endpoint.handler)
    return this
}

// -- Internal helpers --

/**
 * Extracts the path segment after [baseUrl] from a full URL.
 * For example, given `baseUrl = "https://api.example.com/todos"` and
 * `url = "https://api.example.com/todos/srv-1"`, returns `"srv-1"`.
 */
private fun extractTrailingSegment(url: String, baseUrl: String): String {
    val afterBase = url.removePrefix(baseUrl).removePrefix("/")
    // Take only the first path segment (ignore query params or further segments)
    return afterBase.split("?", "/").first()
}

// -- Default response wrappers --

private fun defaultSingleWrapper(record: MockServerRecord): JsonObject = buildJsonObject {
    put("data", record.toJsonObject())
}

private fun defaultListWrapper(records: List<MockServerRecord>): JsonObject = buildJsonObject {
    put("data", JsonArray(records.map { it.toJsonObject() }))
}
