package com.example.sync

import kotlinx.serialization.json.JsonObject

abstract class SyncUpConfig<O : SyncableObject<O>> {
    open fun acceptUploadResponseAsProcessed(
        statusCode: Int,
        responseBody: JsonObject,
        requestTag: String,
    ): Boolean {
        return statusCode != 408 // request timeout
                // Since this is the generic implementation and applicable to many server types,
                // use a broad definition for "server error" encompassing anything in the 500's
                // range.
                && (statusCode !in 500..599)
        // Any other failure attempt should not be retried since that implies the sync
        // was successful, it was just legitimately declined for some reason.
    }

    /**
     * Extracts and deserializes a [O] from the raw server response body received after a
     * sync-up request (create, update, or void) is processed by the server.
     *
     * Different request types may return data in different response shapes. Use [requestTag]
     * to determine how to navigate the response body for the given request type.
     *
     * @param requestTag the tag associated with the request, identifying the request type.
     * @param responseBody the raw JSON response body from the server.
     * @return the deserialized [O], or null if the response does not contain valid data.
     */
    abstract fun fromResponseBody(requestTag: String, responseBody: JsonObject): O?
}
