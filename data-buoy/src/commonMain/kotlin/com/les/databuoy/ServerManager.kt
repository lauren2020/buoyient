package com.les.databuoy

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

public class ServerManager(
    private val serviceBaseHeaders: List<Pair<String, String>>,
    private val globalHeaderProvider: GlobalHeaderProvider? = GlobalHeaderProviderRegistry.provider,
    private val httpClient: HttpClient = HttpClientOverride.httpClient ?: HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
    },
) {
    public fun close() {
        httpClient.close()
    }

    public suspend fun sendRequest(
        httpRequest: HttpRequest,
    ): ServerManagerResponse {
        return try {
            val httpResponse = httpClient.request(httpRequest.endpointUrl) {
                method = HttpMethod(httpRequest.method.value)
                if (httpRequest.method != HttpRequest.HttpMethod.GET) {
                    contentType(ContentType.Application.Json)
                }
                headers {
                    val globalHeaders = globalHeaderProvider?.headers().orEmpty()
                    (globalHeaders + serviceBaseHeaders + httpRequest.additionalHeaders).forEach {
                        val (headerName, headerValue) = it
                        append(headerName, headerValue)
                    }
                }
                if (httpRequest.method != HttpRequest.HttpMethod.GET) {
                    setBody(httpRequest.requestBody.toString())
                }
            }
            val responseBody = try {
                Json.parseToJsonElement(httpResponse.bodyAsText()).jsonObject
            } catch (e: Exception) {
                JsonObject(emptyMap())
            }
            SyncLog.d(TAG, "[${httpRequest.method.value}] response received (${httpResponse.status.value}): $responseBody")
            when {
                httpResponse.status.value in 200..299 -> ServerManagerResponse.Success(
                    statusCode = httpResponse.status.value,
                    responseBody = responseBody,
                    responseEpochTimestamp = httpResponse.responseTime.timestamp,
                )
                httpResponse.status.value in 500..599 -> ServerManagerResponse.ServerError(
                    statusCode = httpResponse.status.value,
                    responseBody = responseBody,
                )
                else -> ServerManagerResponse.Failed(
                    statusCode = httpResponse.status.value,
                    responseBody = responseBody,
                )
            }
        } catch (e: HttpRequestTimeoutException) {
            SyncLog.w(TAG, "[${httpRequest.method.value}] not sent due to request timeout: $e")
            ServerManagerResponse.RequestTimedOut
        } catch (e: Exception) {
            SyncLog.w(TAG, "[${httpRequest.method.value}] not sent due to connection error: $e")
            ServerManagerResponse.ConnectionError
        }
    }

    public sealed class ServerManagerResponse {
        /**
         * A request was sent and the server returned a 2xx status code.
         *
         * @property statusCode - the 2xx status code of the server response.
         * @property responseBody - the json response body from the server.
         * @property responseEpochTimestamp - the time in epoch seconds that the response was started.
         */
        public class Success(
            public val statusCode: Int,
            public val responseBody: JsonObject,
            public val responseEpochTimestamp: Long,
        ) : ServerManagerResponse()

        /**
         * A request was sent and the server returned a non-2xx, non-5xx status code
         * (e.g. 3xx, 4xx).
         *
         * @property statusCode - the status code of the server response.
         * @property responseBody - the json response body from the server.
         */
        public class Failed(
            public val statusCode: Int,
            public val responseBody: JsonObject,
        ) : ServerManagerResponse()

        /**
         * A request was sent and the server returned a 5xx status code, indicating a
         * server-side error.
         *
         * @property statusCode - the 5xx status code of the server response.
         * @property responseBody - the json response body from the server, if any.
         */
        public class ServerError(
            public val statusCode: Int,
            public val responseBody: JsonObject,
        ) : ServerManagerResponse()

        /**
         * A request was attempted but timed out before a response was received.
         */
        public object RequestTimedOut : ServerManagerResponse()

        /**
         * A request was not attempted due to connectivity failure.
         */
        public object ConnectionError : ServerManagerResponse()
    }

    public companion object {
        public const val TAG: String = "SyncableObjectService:HttpManager"
    }
}
