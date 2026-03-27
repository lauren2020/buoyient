package com.les.buoyient.serviceconfigs

import com.les.buoyient.datatypes.ResponseUnpacker
import com.les.buoyient.SyncableObject
import kotlinx.serialization.json.JsonObject

/**
 * The result of parsing a server response in [SyncUpConfig.fromResponseBody].
 *
 * Determines what the sync engine does with the pending request and local data after
 * a sync-up upload completes. See each subclass for details on the data flow.
 */
public sealed class SyncUpResult<O : SyncableObject<O>> {
    public abstract val data: O?

    /**
     * The server accepted the request and returned valid data.
     *
     * [data] is treated as the **authoritative server state** for this object. What happens
     * next depends on whether more pending requests remain in the queue for this object:
     *
     * - **No pending requests remain:** [data] **replaces** the local entry entirely — it
     *   becomes the new local copy and the new server baseline.
     * - **Pending requests remain:** [data] becomes the new server baseline, and the
     *   remaining pending changes are **rebased** on top of it via the service's
     *   [SyncableObjectRebaseHandler] (3-way merge). If the merge produces conflicts the
     *   object is marked [SyncableObject.SyncStatus.Companion.CONFLICT] for manual resolution.
     *
     * In either case the pending request that was just uploaded is removed from the queue.
     */
    public class Success<O : SyncableObject<O>>(override val data: O) : SyncUpResult<O>()
    public sealed class Failed<O : SyncableObject<O>> : SyncUpResult<O>() {
        override val data: O? = null

        /** The pending request should be retried on the next sync cycle. */
        public class Retry<O : SyncableObject<O>> : Failed<O>()

        /**
         * The pending request should be removed from the queue (e.g., permanently rejected
         * by the server). The local entry is kept as-is since no server data was returned.
         */
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
     * The object returned inside [SyncUpResult.Success] is treated as **authoritative server
     * state** — it either replaces the local entry outright (when no further pending requests
     * remain) or serves as the new baseline for rebasing remaining pending changes. See
     * [SyncUpResult.Success] for the full data-flow contract.
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
