package com.les.databuoy

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Builds the HTTP request for a create operation.
 *
 * @param data the object being created.
 * @param idempotencyKey a unique key to ensure the request is idempotent.
 * @param isOffline whether the device is currently offline — the request will be queued for later.
 * @param attemptedServerRequest if a prior attempt was made to the server (e.g. timed out),
 *  this holds that request so the builder can account for idempotent retry concerns.
 */
fun interface CreateRequestBuilder<O : SyncableObject<O>> {
    fun buildRequest(
        data: O,
        idempotencyKey: String,
        isOffline: Boolean,
        attemptedServerRequest: HttpRequest?,
    ): HttpRequest
}

/**
 * Builds the HTTP request for an update operation using the sparse diff between the last
 * synced state and the current state.
 *
 * @param lastSyncedData the last known server-synced version of the object.
 * @param updatedData the current local version containing the updates.
 * @param idempotencyKey a unique key to ensure the request is idempotent.
 * @param isAsync indicates if the request being built is going to processed online of offline.
 *  This value be true if being processed async, and false if be processed synchronously.
 * @param attemptedServerRequest if a prior attempt was made to the server (e.g. timed out),
 *  this holds that request so the builder can account for idempotent retry concerns.
 */
fun interface UpdateRequestBuilder<O : SyncableObject<O>> {
    fun buildRequest(
        lastSyncedData: O,
        updatedData: O,
        idempotencyKey: String,
        isAsync: Boolean,
        attemptedServerRequest: HttpRequest?,
    ): HttpRequest
}

/**
 * Builds the HTTP request for a void (delete) operation.
 *
 * @param data the object being voided.
 * @param serverAttemptedPendingRequests pending requests for this object that have already
 *  been attempted on the server — useful for building requests that account for partial
 *  server-side state.
 */
fun interface VoidRequestBuilder<O : SyncableObject<O>> {
    fun buildRequest(
        data: O,
        serverAttemptedPendingRequests: List<PendingSyncRequest<O>>,
    ): HttpRequest
}

/**
 * Extracts data [O] from a server response. Used by all operations (create, update,
 * void, get) that receive a server response.
 *
 * @param responseBody the JSON response body from the server.
 * @param statusCode the HTTP status code from the server response.
 * @param syncStatus the sync status representing the state of the object after this operation.
 * @return the unpacked data, or null if the response does not contain valid data.
 */
fun interface ResponseUnpacker<O : SyncableObject<O>> {
    fun unpack(responseBody: JsonObject, statusCode: Int, syncStatus: SyncableObject.SyncStatus): O?

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Creates a [ResponseUnpacker] that extracts data from a single JSON key.
         * Handles the common REST pattern where responses look like `{ "item": { ... } }`.
         *
         * @param key the top-level JSON key containing the object (e.g., "order", "item").
         * @param serializer the [KSerializer] for deserializing the object.
         */
        fun <O : SyncableObject<O>> fromKey(
            key: String,
            serializer: KSerializer<O>,
        ): ResponseUnpacker<O> = ResponseUnpacker { responseBody, _, _ ->
            val element = responseBody[key]?.jsonObject ?: return@ResponseUnpacker null
            json.decodeFromJsonElement(serializer, element)
        }

        /**
         * Creates a [ResponseUnpacker] that selects the JSON key based on the response structure.
         * Useful when different operations return data under different keys, but you want a
         * single unpacker that handles all of them.
         *
         * Tries each key in order and returns the first successful parse.
         *
         * @param keys the top-level JSON keys to try, in order (e.g., "order", "payment").
         * @param serializer the [KSerializer] for deserializing the object.
         */
        fun <O : SyncableObject<O>> fromKeys(
            keys: List<String>,
            serializer: KSerializer<O>,
        ): ResponseUnpacker<O> = ResponseUnpacker { responseBody, _, _ ->
            for (key in keys) {
                val element = responseBody[key]?.jsonObject ?: continue
                return@ResponseUnpacker json.decodeFromJsonElement(serializer, element)
            }
            null
        }
    }
}

/**
 * Merges an update request into an existing create request when using the squash
 * queue strategy. This allows pending create + update pairs to be collapsed into a
 * single create request.
 *
 * @param createRequest the original pending create request.
 * @param updateRequest the new update request to merge into the create.
 * @return a single merged HTTP request that represents both operations.
 */
fun interface SquashRequestMerger {
    fun merge(createRequest: HttpRequest, updateRequest: HttpRequest): HttpRequest
}
