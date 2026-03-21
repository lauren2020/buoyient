package com.les.databuoy.testing

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Extension functions that wire a [MockServerCollection] to a [MockEndpointRouter]
 * as automatic CRUD handlers. This is the integration layer between the stateful
 * mock server store and the mock HTTP routing infrastructure.
 *
 * ## Quick start
 *
 * ```kotlin
 * val store = MockServerStore()
 * val todos = store.collection("todos")
 * val router = MockEndpointRouter()
 *
 * router.registerCrudHandlers(
 *     collection = todos,
 *     baseUrl = "https://api.example.com/todos",
 * )
 *
 * // Now POST /todos creates a record, GET /todos lists all, etc.
 * val serverManager = router.buildServerManager()
 * ```
 *
 * The [responseWrapper] and [listResponseWrapper] parameters control the JSON
 * envelope shape. Override them to match your API's actual response format.
 */

/**
 * Registers standard CRUD handlers on this router, backed by [collection]:
 *
 * - `POST baseUrl` → [MockServerCollection.create] → 201
 * - `PUT baseUrl/{serverId}` → [MockServerCollection.update] → 200 (or 404)
 * - `GET baseUrl/{serverId}` → [MockServerCollection.get] → 200 (or 404)
 * - `GET baseUrl` → [MockServerCollection.getAll] → 200
 * - `DELETE baseUrl/{serverId}` → [MockServerCollection.void] → 200 (or 404)
 *
 * @param collection the server-side collection to back the handlers.
 * @param baseUrl the base URL for the resource (e.g. `"https://api.example.com/todos"`).
 *   Individual resource URLs are `baseUrl/{serverId}`.
 * @param responseWrapper transforms a single [MockServerRecord] into the JSON response body.
 *   Defaults to `{"data": <record>}`.
 * @param listResponseWrapper transforms a list of records into the JSON response body.
 *   Defaults to `{"data": [<records>]}`.
 * @return this router, for chaining.
 */
fun MockEndpointRouter.registerCrudHandlers(
    collection: MockServerCollection,
    baseUrl: String,
    responseWrapper: (MockServerRecord) -> JsonObject = ::defaultSingleWrapper,
    listResponseWrapper: (List<MockServerRecord>) -> JsonObject = ::defaultListWrapper,
): MockEndpointRouter {
    // POST baseUrl -> create
    onPost(baseUrl) { request ->
        val record = collection.create(request.body)
        MockResponse(statusCode = 201, body = responseWrapper(record))
    }

    // PUT baseUrl/* -> update by serverId extracted from URL
    onPut("$baseUrl/*") { request ->
        val serverId = extractTrailingSegment(request.url, baseUrl)
        val record = collection.update(serverId, request.body)
        if (record != null) {
            MockResponse(statusCode = 200, body = responseWrapper(record))
        } else {
            MockResponse(statusCode = 404, body = JsonObject(emptyMap()))
        }
    }

    // GET baseUrl/* -> get single by serverId (registered before the list handler
    // because MockEndpointRouter uses first-match routing, and the trailing-wildcard
    // pattern is more specific than the exact-match list pattern)
    onGet("$baseUrl/*") { request ->
        val serverId = extractTrailingSegment(request.url, baseUrl)
        val record = collection.get(serverId)
        if (record != null) {
            MockResponse(statusCode = 200, body = responseWrapper(record))
        } else {
            MockResponse(statusCode = 404, body = JsonObject(emptyMap()))
        }
    }

    // GET baseUrl -> list all
    onGet(baseUrl) { _ ->
        MockResponse(statusCode = 200, body = listResponseWrapper(collection.getAll()))
    }

    // DELETE baseUrl/* -> void
    onDelete("$baseUrl/*") { request ->
        val serverId = extractTrailingSegment(request.url, baseUrl)
        val record = collection.void(serverId)
        if (record != null) {
            MockResponse(statusCode = 200, body = responseWrapper(record))
        } else {
            MockResponse(statusCode = 404, body = JsonObject(emptyMap()))
        }
    }

    return this
}

/**
 * Registers a POST-based sync-down handler backed by [collection].
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
 * @return this router, for chaining.
 */
fun MockEndpointRouter.registerSyncDownHandler(
    collection: MockServerCollection,
    urlPattern: String,
    listResponseWrapper: (List<MockServerRecord>) -> JsonObject = ::defaultListWrapper,
    extractTimestamp: (RecordedRequest) -> Long? = { null },
): MockEndpointRouter {
    onPost(urlPattern) { request ->
        val since = extractTimestamp(request)
        val records = if (since != null) {
            collection.getUpdatedSince(since)
        } else {
            collection.getAll()
        }
        MockResponse(statusCode = 200, body = listResponseWrapper(records))
    }
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
