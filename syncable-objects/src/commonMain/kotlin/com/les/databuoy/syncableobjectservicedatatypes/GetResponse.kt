package com.les.databuoy.syncableobjectservicedatatypes

import kotlinx.serialization.json.JsonObject

public sealed class GetResponse<O> {
    public class ReceivedServerResponse<O>(
        public val statusCode: Int,
        public val responseBody: JsonObject,
        public val data: O?,
    ) : GetResponse<O>()

    public class RetrievedFromLocalStore<O>(public val data: O) : GetResponse<O>()

    public class NotFound<O> : GetResponse<O>()

    public class NoInternetConnection<O> : GetResponse<O>()

    public class RequestTimedOut<O> : GetResponse<O>()
}