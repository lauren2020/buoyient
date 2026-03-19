package com.example.sync

import io.ktor.client.HttpClient
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

class ServerManager(
    private val serviceBaseHeaders: List<Pair<String, String>>,
    private val logger: SyncLogger,
    private val httpClient: HttpClient = HttpClient(),
) {
    fun close() {
        httpClient.close()
    }

    suspend fun sendRequest(
        httpRequest: HttpRequest,
    ): ServerManagerResponse {
        return try {
            val httpResponse = httpClient.request(httpRequest.endpointUrl) {
                method = HttpMethod(httpRequest.method.value)
                if (httpRequest.method != HttpRequest.HttpMethod.GET) {
                    contentType(ContentType.Application.Json)
                }
                headers {
                    (serviceBaseHeaders + httpRequest.additionalHeaders).forEach {
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
            logger.d(TAG, "[${httpRequest.method.value}] response received (${httpResponse.status.value}): $responseBody")
            ServerManagerResponse.ServerResponse(
                statusCode = httpResponse.status.value,
                responseBody = responseBody,
                responseEpochTimestamp = httpResponse.responseTime.timestamp,
            )
        } catch (e: Exception) {
            logger.w(TAG, "[${httpRequest.method.value}] not sent due to connection error: $e")
            ServerManagerResponse.ConnectionError
        }
    }

    sealed class ServerManagerResponse {
        /**
         * A request was sent and the provided data resulted from the attempt.
         *
         * @property statusCode - the status code of the server response.
         * @property responseBody - the json response body from the server.
         * @property responseEpochTimestamp - the time in epoch seconds that the response was started.
         */
        class ServerResponse(
            val statusCode: Int,
            val responseBody: JsonObject,
            val responseEpochTimestamp: Long,
        ) : ServerManagerResponse()

        /**
         * A request was not attempted due to connectivity failure.
         */
        object ConnectionError : ServerManagerResponse()
    }

    companion object {
        const val TAG = "SyncableObjectService:HttpManager"
    }
}
