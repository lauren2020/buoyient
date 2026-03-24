package com.les.databuoy

import kotlinx.serialization.json.JsonObject

sealed class SyncUpResult<out O> {
    class Success<O>(val data: O) : SyncUpResult<O>()
    sealed class Failed : SyncUpResult<Nothing>() {
        /** The pending request should be retried on the next sync cycle. */
        data object Retry : Failed()
        /** The pending request should be removed from the queue (e.g., permanently rejected by the server). */
        data object RemovePendingRequest : Failed()
    }
}

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
     * @return [SyncUpResult.Success] containing the deserialized [O],
     *         [SyncUpResult.Failed.Retry] to leave the pending request in the queue for a future attempt, or
     *         [SyncUpResult.Failed.RemovePendingRequest] to remove it from the queue (e.g., permanently rejected).
     */
    abstract fun fromResponseBody(requestTag: String, responseBody: JsonObject): SyncUpResult<O>
}
