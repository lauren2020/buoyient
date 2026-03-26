package com.les.databuoy

import kotlinx.serialization.json.JsonObject

public sealed class SyncUpResult<O : SyncableObject<O>> {
    public abstract val data: O?

    public class Success<O : SyncableObject<O>>(override val data: O) : SyncUpResult<O>()
    public sealed class Failed<O : SyncableObject<O>> : SyncUpResult<O>() {
        override val data: O? = null

        /** The pending request should be retried on the next sync cycle. */
        public class Retry<O : SyncableObject<O>> : Failed<O>()
        /** The pending request should be removed from the queue (e.g., permanently rejected by the server). */
        public class RemovePendingRequest<O : SyncableObject<O>> : Failed<O>()
    }
}

public abstract class SyncUpConfig<O : SyncableObject<O>> {
    public open fun acceptUploadResponseAsProcessed(
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
    public abstract fun fromResponseBody(requestTag: String, responseBody: JsonObject): SyncUpResult<O>

    public companion object {
        /**
         * Creates a [SyncUpConfig] that delegates response parsing to a [ResponseUnpacker].
         * The unpacker's result is wrapped in [SyncUpResult.Success] if non-null, or
         * [SyncUpResult.Failed.RemovePendingRequest] if null (indicating the response
         * couldn't be parsed — the request is dropped rather than retried indefinitely).
         *
         * This is useful when your synchronous [ResponseUnpacker] and async sync-up
         * parsing logic are identical, allowing you to define the parsing once.
         */
        public fun <O : SyncableObject<O>> fromUnpacker(
            unpacker: ResponseUnpacker<O>,
        ): SyncUpConfig<O> = object : SyncUpConfig<O>() {
            override fun fromResponseBody(
                requestTag: String,
                responseBody: JsonObject,
            ): SyncUpResult<O> {
                val data = unpacker.unpack(
                    responseBody,
                    statusCode = 200,
                    syncStatus = SyncableObject.SyncStatus.LocalOnly,
                )
                return if (data != null) {
                    SyncUpResult.Success(data)
                } else {
                    SyncUpResult.Failed.RemovePendingRequest()
                }
            }
        }
    }
}
