package com.les.databuoy

import kotlinx.serialization.json.JsonObject

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
 */
fun interface UpdateRequestBuilder<O : SyncableObject<O>> {
    fun buildRequest(
        lastSyncedData: O,
        updatedData: O,
        idempotencyKey: String,
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
