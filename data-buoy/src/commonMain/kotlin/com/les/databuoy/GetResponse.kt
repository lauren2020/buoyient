package com.les.databuoy

import kotlinx.serialization.json.JsonObject

sealed class GetResponse<O> {
    class ReceivedServerResponse<O>(
        val statusCode: Int,
        val responseBody: JsonObject,
        val data: O?,
    ) : GetResponse<O>()

    class RetrievedFromLocalStore<O>(val data: O) : GetResponse<O>()

    class NotFound<O> : GetResponse<O>()

    class NoInternetConnection<O> : GetResponse<O>()

    class RequestTimedOut<O> : GetResponse<O>()
}
