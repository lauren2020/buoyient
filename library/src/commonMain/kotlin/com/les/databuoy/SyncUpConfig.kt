package com.les.databuoy

import kotlinx.serialization.json.JsonObject

sealed class SyncUpResult<O : SyncableObject<O>> {
    abstract val data: O?

    class Success<O : SyncableObject<O>>(override val data: O) : SyncUpResult<O>()
    sealed class Failed<O : SyncableObject<O>> : SyncUpResult<O>() {
        override val data: O? = null

        /** The pending request should be retried on the next sync cycle. */
        class Retry<O : SyncableObject<O>> : Failed<O>()
        /** The pending request should be removed from the queue (e.g., permanently rejected by the server). */
        class RemovePendingRequest<O : SyncableObject<O>> : Failed<O>()
    }
}

abstract class SyncUpConfig<O : SyncableObject<O>> {
    open fun acceptUploadResponseAsProcessed(
        statusCode: Int,
        responseBody: JsonObject,
        requestTag: String,
    ): Boolean {
        return statusCode != 408 // request timeout
                && statusCode != 429 // too many requests (rate limited)
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
