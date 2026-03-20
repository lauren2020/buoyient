package com.example.sync

import kotlinx.serialization.json.JsonObject

interface ServerProcessingConfig<T> {
    val syncFetchConfig: SyncFetchConfig<T>
    val syncUpConfig: SyncUpConfig
    val headers: List<Pair<String, String>>
    fun fromServerProtoJson(json: JsonObject): T

    /**
     * Extracts and deserializes a [T] from the raw server response body received after a
     * sync-up request (create, update, or void) is processed by the server.
     *
     * Different request types may return data in different response shapes. Use [requestTag]
     * to determine how to navigate the response body for the given request type.
     *
     * @param requestTag the tag associated with the request, identifying the request type.
     * @param responseBody the raw JSON response body from the server.
     * @return the deserialized [T], or null if the response does not contain valid data.
     */
    fun fromSyncUpResponseBody(requestTag: String, responseBody: JsonObject): T?
}