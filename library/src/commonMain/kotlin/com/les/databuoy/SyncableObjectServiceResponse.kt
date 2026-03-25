package com.les.databuoy

import kotlinx.serialization.json.JsonObject

sealed class SyncableObjectServiceResponse<O> {
    sealed class Finished<O> : SyncableObjectServiceResponse<O>() {
        class StoredLocally<O>(val updatedData: O) : Finished<O>()

        class NetworkResponseReceived<O>(
            val statusCode: Int,
            val responseBody: JsonObject,
            val updatedData: O?,
        ) : Finished<O>()
    }

    class NoInternetConnection<O> : SyncableObjectServiceResponse<O>()

    class InvalidRequest<O> : SyncableObjectServiceResponse<O>()

    class RequestTimedOut<O> : SyncableObjectServiceResponse<O>()

    class LocalStoreFailed<O>(val exception: Exception) : SyncableObjectServiceResponse<O>()
}
