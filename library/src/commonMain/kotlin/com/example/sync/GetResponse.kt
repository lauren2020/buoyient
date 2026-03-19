package com.example.sync

import kotlinx.serialization.json.JsonObject

sealed class GetResponse<O> {
    class ReceivedServerResponse<O>(
        val statusCode: Int,
        val responseBody: JsonObject,
        val data: O?,
    ) : GetResponse<O>()

    class RetrievedFromLocalStore<O>(val data: O) : GetResponse<O>()

    class NotFound<O> : GetResponse<O>()
}
