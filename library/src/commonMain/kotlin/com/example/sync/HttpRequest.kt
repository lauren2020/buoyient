package com.example.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class HttpRequest(
    val method: HttpMethod,
    val endpointUrl: String,
    val requestBody: JsonObject,
    val additionalHeaders: List<Pair<String, String>> = emptyList(),
) {
    enum class HttpMethod(val value: String) {
        DELETE("DELETE"),
        GET("GET"),
        POST("POST"),
        PUT("PUT");

        companion object {
            @OptIn(ExperimentalStdlibApi::class)
            fun fromValue(value: String): HttpMethod = entries.first { it.value == value }
        }
    }

    fun toJson() = buildJsonObject {
        put(METHOD_TAG, method.value)
        put(ENDPOINT_URL_TAG, endpointUrl)
        put(REQUEST_BODY_TAG, requestBody)
    }

    fun resolveEndpoint(serverId: String): HttpRequest? {
        if (!endpointUrl.contains(SERVER_ID_PLACEHOLDER)) return null
        return HttpRequest(
            method = method,
            endpointUrl = endpointUrl.replace(SERVER_ID_PLACEHOLDER, serverId),
            requestBody = requestBody,
            additionalHeaders = additionalHeaders,
        )
    }

    fun resolveBodyVersion(version: String): HttpRequest? {
        val bodyString = requestBody.toString()
        if (!bodyString.contains(VERSION_PLACEHOLDER)) return null
        val resolvedBody = Json.parseToJsonElement(
            bodyString.replace(VERSION_PLACEHOLDER, version)
        ).jsonObject
        return HttpRequest(
            method = method,
            endpointUrl = endpointUrl,
            requestBody = resolvedBody,
            additionalHeaders = additionalHeaders,
        )
    }

    fun resolveBodyServerId(serverId: String): HttpRequest? {
        val bodyString = requestBody.toString()
        if (!bodyString.contains(SERVER_ID_PLACEHOLDER)) return null
        val resolvedBody = Json.parseToJsonElement(
            bodyString.replace(SERVER_ID_PLACEHOLDER, serverId)
        ).jsonObject
        return HttpRequest(
            method = method,
            endpointUrl = endpointUrl,
            requestBody = resolvedBody,
            additionalHeaders = additionalHeaders,
        )
    }

    companion object {
        const val SERVER_ID_PLACEHOLDER = "{serverId}"
        const val VERSION_PLACEHOLDER = "{version}"
        const val METHOD_TAG = "method"
        const val ENDPOINT_URL_TAG = "endpoint"
        const val REQUEST_BODY_TAG = "request_body"

        fun fromJson(json: JsonObject) = HttpRequest(
            method = HttpMethod.fromValue(json[METHOD_TAG]!!.jsonPrimitive.content),
            endpointUrl = json[ENDPOINT_URL_TAG]!!.jsonPrimitive.content,
            requestBody = json[REQUEST_BODY_TAG]!!.jsonObject,
        )
    }
}
