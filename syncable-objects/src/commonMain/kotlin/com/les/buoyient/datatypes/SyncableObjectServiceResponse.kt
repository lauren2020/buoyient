package com.les.buoyient.datatypes

import kotlinx.serialization.json.JsonObject

/**
 * Result of a [com.les.buoyient.SyncableObjectService] operation (create, update, or void).
 *
 * Every call to [com.les.buoyient.SyncableObjectService.create], [com.les.buoyient.SyncableObjectService.update], or
 * [com.les.buoyient.SyncableObjectService.void] returns one of these variants, indicating whether the
 * operation succeeded, failed, or was queued offline.
 *
 * @param O the domain model type that implements [com.les.buoyient.SyncableObject].
 */
public sealed class SyncableObjectServiceResponse<O> {
    /**
     * The operation completed successfully.
     */
    public sealed class Success<O> : SyncableObjectServiceResponse<O>() {
        /**
         * The device is offline — the request was persisted locally and will sync when
         * connectivity is restored.
         *
         * @property updatedData the locally-stored object with its current state.
         */
        public class StoredLocally<O>(public val updatedData: O) : Success<O>()

        /**
         * The server accepted the request and returned a response.
         *
         * @property statusCode HTTP status code from the server.
         * @property responseBody raw JSON response body.
         * @property updatedData the deserialized domain object, or `null` if the response
         *   could not be parsed into [O].
         */
        public class NetworkResponseReceived<O>(
            public val statusCode: Int,
            public val responseBody: JsonObject,
            public val updatedData: O?,
        ) : Success<O>()
    }

    /**
     * The operation failed.
     */
    public sealed class Failed<O> : SyncableObjectServiceResponse<O>() {
        /**
         * The server returned a non-success HTTP status.
         *
         * @property statusCode HTTP status code from the server.
         * @property responseBody raw JSON response body.
         */
        public class NetworkResponseReceived<O>(
            public val statusCode: Int,
            public val responseBody: JsonObject,
        ) : Failed<O>()

        /**
         * Writing to the local SQLite store failed.
         *
         * @property exception the underlying exception.
         */
        public class LocalStoreFailed<O>(public val exception: Exception) : Failed<O>()
    }

    /** The device has no internet connection and the request could not be queued. */
    public class NoInternetConnection<O> : SyncableObjectServiceResponse<O>()

    /** The request was invalid and was not sent (e.g., missing required fields). */
    public class InvalidRequest<O> : SyncableObjectServiceResponse<O>()

    /**
     * The request timed out waiting for a server response.
     *
     * @property idempotencyKey the key that can be used to safely retry the request.
     */
    public class RequestTimedOut<O>(public val idempotencyKey: String) : SyncableObjectServiceResponse<O>()

    /**
     * The server returned a 5xx error.
     *
     * @property statusCode HTTP status code.
     * @property responseBody raw JSON response body, if available.
     * @property idempotencyKey the key that can be used to safely retry the request.
     */
    public class ServerError<O>(public val statusCode: Int, public val responseBody: JsonObject?, public val idempotencyKey: String) : SyncableObjectServiceResponse<O>()
}