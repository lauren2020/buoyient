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

public class HttpRequest(
    public val method: HttpMethod,
    public val endpointUrl: String,
    public val requestBody: JsonObject,
    public val additionalHeaders: List<Pair<String, String>> = emptyList(),
) {
    public enum class HttpMethod(public val value: String) {
        DELETE("DELETE"),
        GET("GET"),
        PATCH("PATCH"),
        POST("POST"),
        PUT("PUT");

        public companion object {
            @OptIn(ExperimentalStdlibApi::class)
            public fun fromValue(value: String): HttpMethod = entries.first { it.value == value }
        }
    }

    public fun toJson(): JsonObject = buildJsonObject {
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

    public fun resolveEndpoint(serverId: String): HttpRequest? {
        if (!endpointUrl.contains(SERVER_ID_PLACEHOLDER)) return null
        return HttpRequest(
            method = method,
            endpointUrl = endpointUrl.replace(SERVER_ID_PLACEHOLDER, serverId),
            requestBody = requestBody,
            additionalHeaders = additionalHeaders,
        )
    }

    public fun resolveBodyVersion(version: String): HttpRequest? {
        val resolved = replacePlaceholderInJson(requestBody, VERSION_PLACEHOLDER, version)
            ?: return null
        return HttpRequest(
            method = method,
            endpointUrl = endpointUrl,
            requestBody = resolved,
            additionalHeaders = additionalHeaders,
        )
    }

    public fun resolveBodyServerId(serverId: String): HttpRequest? {
        val resolved = replacePlaceholderInJson(requestBody, SERVER_ID_PLACEHOLDER, serverId)
            ?: return null
        return HttpRequest(
            method = method,
            endpointUrl = endpointUrl,
            requestBody = resolved,
            additionalHeaders = additionalHeaders,
        )
    }

    /**
     * Returns `true` if this request's endpoint URL or request body contains any
     * cross-service placeholders (i.e., `{cross:serviceName:clientId}`).
     */
    public fun containsCrossServicePlaceholders(): Boolean {
        return endpointUrl.contains(CROSS_SERVICE_PLACEHOLDER_PREFIX) ||
            requestBody.toString().contains(CROSS_SERVICE_PLACEHOLDER_PREFIX)
    }

    /**
     * Resolves all cross-service placeholders in this request's endpoint URL and body.
     *
     * Each placeholder has the form `{cross:serviceName:clientId}` and is replaced with
     * the server ID returned by [resolver]. If the resolver returns `null` for any
     * placeholder (meaning the dependency hasn't synced yet), this method returns `null`
     * to signal that the request should be skipped until its dependencies are ready.
     *
     * @param resolver looks up a server ID by (serviceName, clientId). Returns `null`
     *   if the referenced object hasn't been assigned a server ID yet.
     * @return a new [HttpRequest] with all cross-service placeholders resolved,
     *   or `null` if any dependency is unresolved.
     */
    public fun resolveCrossServicePlaceholders(
        resolver: (serviceName: String, clientId: String) -> String?,
    ): HttpRequest? {
        val fullText = endpointUrl + requestBody.toString()
        val matches = CROSS_SERVICE_PATTERN.findAll(fullText).toList()
        if (matches.isEmpty()) return null

        // Resolve all unique placeholders up front so we fail fast.
        val replacements = mutableMapOf<String, String>()
        for (match in matches) {
            val placeholder = match.value
            if (placeholder in replacements) continue
            val serviceName = match.groupValues[1]
            val clientId = match.groupValues[2]
            val serverId = resolver(serviceName, clientId) ?: return null
            replacements[placeholder] = serverId
        }

        // Apply replacements to endpoint URL.
        var resolvedUrl = endpointUrl
        for ((placeholder, serverId) in replacements) {
            resolvedUrl = resolvedUrl.replace(placeholder, serverId)
        }

        // Apply replacements to request body.
        var resolvedBody = requestBody
        for ((placeholder, serverId) in replacements) {
            resolvedBody = replacePlaceholderInJson(resolvedBody, placeholder, serverId) ?: resolvedBody
        }

        return HttpRequest(
            method = method,
            endpointUrl = resolvedUrl,
            requestBody = resolvedBody,
            additionalHeaders = additionalHeaders,
        )
    }

    public companion object {
        public const val SERVER_ID_PLACEHOLDER: String = "{serverId}"
        public const val VERSION_PLACEHOLDER: String = "{version}"
        private const val CROSS_SERVICE_PLACEHOLDER_PREFIX = "{cross:"
        private val CROSS_SERVICE_PATTERN = Regex("""\{cross:([^:}]+):([^}]+)\}""")

        /**
         * Creates a cross-service placeholder that will be resolved at sync-up time
         * to the server ID of the referenced object.
         *
         * Use this when building an [HttpRequest] for an operation that depends on
         * another service's object being synced first. For example, creating a Payment
         * that references an Order's server ID:
         *
         * ```kotlin
         * val orderIdField = HttpRequest.crossServicePlaceholder("orders", order.clientId)
         * // Use orderIdField in the request body where the order's server ID is needed
         * ```
         *
         * The placeholder is resolved automatically during sync-up. If the referenced
         * object hasn't synced yet, the request is skipped and retried on the next cycle.
         *
         * @param serviceName the [SyncableObjectService.serviceName] of the dependency
         * @param clientId the [SyncableObject.clientId] of the referenced object
         */
        public fun crossServicePlaceholder(serviceName: String, clientId: String): String =
            "{cross:$serviceName:$clientId}"

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
        public const val METHOD_TAG: String = "method"
        public const val ENDPOINT_URL_TAG: String = "endpoint"
        public const val REQUEST_BODY_TAG: String = "request_body"
        public const val ADDITIONAL_HEADERS_TAG: String = "additional_headers"
        private const val HEADER_NAME_TAG = "name"
        private const val HEADER_VALUE_TAG = "value"

        public fun fromJson(json: JsonObject): HttpRequest = HttpRequest(
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
