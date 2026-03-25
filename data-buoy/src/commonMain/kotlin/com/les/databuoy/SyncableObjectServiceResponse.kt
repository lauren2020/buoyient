package com.les.databuoy

import kotlinx.serialization.json.JsonObject

sealed class SyncableObjectServiceResponse<O> {
    sealed class Success<O> : SyncableObjectServiceResponse<O>() {
        class StoredLocally<O>(val updatedData: O) : Success<O>()

        class NetworkResponseReceived<O>(
            val statusCode: Int,
            val responseBody: JsonObject,
            val updatedData: O?,
        ) : Success<O>()
    }

    sealed class Failed<O> : SyncableObjectServiceResponse<O>() {
        class NetworkResponseReceived<O>(
            val statusCode: Int,
            val responseBody: JsonObject,
        ) : Failed<O>()

        class LocalStoreFailed<O>(val exception: Exception) : Failed<O>()
    }

    class NoInternetConnection<O> : SyncableObjectServiceResponse<O>()

    class InvalidRequest<O> : SyncableObjectServiceResponse<O>()

    class RequestTimedOut<O>(val idempotencyKey: String) : SyncableObjectServiceResponse<O>()

    class ServerError<O>(val statusCode: Int, val responseBody: JsonObject?, val idempotencyKey: String) : SyncableObjectServiceResponse<O>()
}
