package com.les.databuoy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
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
        PATCH("PATCH"),
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
        if (additionalHeaders.isNotEmpty()) {
            put(ADDITIONAL_HEADERS_TAG, JsonArray(additionalHeaders.map { (name, value) ->
                buildJsonObject {
                    put(HEADER_NAME_TAG, name)
                    put(HEADER_VALUE_TAG, value)
                }
            }))
        }
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
        val resolved = replacePlaceholderInJson(requestBody, VERSION_PLACEHOLDER, version)
            ?: return null
        return HttpRequest(
            method = method,
            endpointUrl = endpointUrl,
            requestBody = resolved,
            additionalHeaders = additionalHeaders,
        )
    }

    fun resolveBodyServerId(serverId: String): HttpRequest? {
        val resolved = replacePlaceholderInJson(requestBody, SERVER_ID_PLACEHOLDER, serverId)
            ?: return null
        return HttpRequest(
            method = method,
            endpointUrl = endpointUrl,
            requestBody = resolved,
            additionalHeaders = additionalHeaders,
        )
    }

    companion object {
        const val SERVER_ID_PLACEHOLDER = "{serverId}"
        const val VERSION_PLACEHOLDER = "{version}"

        /**
         * Walks a [JsonElement] tree and replaces [JsonPrimitive] string values that contain
         * [placeholder] with [replacement]. Returns null if no replacements were made.
         */
        private fun replacePlaceholderInJson(
            element: JsonObject,
            placeholder: String,
            replacement: String,
        ): JsonObject? {
            var replaced = false
            fun replaceIn(el: JsonElement): JsonElement = when (el) {
                is JsonPrimitive -> {
                    if (el.isString && el.content.contains(placeholder)) {
                        replaced = true
                        JsonPrimitive(el.content.replace(placeholder, replacement))
                    } else el
                }
                is JsonObject -> JsonObject(el.mapValues { (_, v) -> replaceIn(v) })
                is JsonArray -> JsonArray(el.map { replaceIn(it) })
            }
            val result = replaceIn(element)
            return if (replaced) result as JsonObject else null
        }
        const val METHOD_TAG = "method"
        const val ENDPOINT_URL_TAG = "endpoint"
        const val REQUEST_BODY_TAG = "request_body"
        const val ADDITIONAL_HEADERS_TAG = "additional_headers"
        private const val HEADER_NAME_TAG = "name"
        private const val HEADER_VALUE_TAG = "value"

        fun fromJson(json: JsonObject) = HttpRequest(
            method = HttpMethod.fromValue(json[METHOD_TAG]!!.jsonPrimitive.content),
            endpointUrl = json[ENDPOINT_URL_TAG]!!.jsonPrimitive.content,
            requestBody = json[REQUEST_BODY_TAG]!!.jsonObject,
            additionalHeaders = json[ADDITIONAL_HEADERS_TAG]?.jsonArray?.map { element ->
                val header = element.jsonObject
                header[HEADER_NAME_TAG]!!.jsonPrimitive.content to header[HEADER_VALUE_TAG]!!.jsonPrimitive.content
            } ?: emptyList(),
        )
    }
}
