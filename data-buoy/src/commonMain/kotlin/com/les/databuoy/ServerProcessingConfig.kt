package com.les.databuoy

import kotlinx.serialization.json.JsonObject

interface ServerProcessingConfig<O : SyncableObject<O>> {
    /**
     * The configuration for how syncing server data down and upserting it to the local store
     * should be handled.
     */
    val syncFetchConfig: SyncFetchConfig<O>

    /**
     * The configuration for how syncing local data up to the server should be handled.
     */
    val syncUpConfig: SyncUpConfig<O>

    /**
     * Headers specific to this service, applied to every request it makes.
     *
     * For auth headers shared across all services, use [DataBuoy.globalHeaderProvider] instead.
     * At request time, headers are merged in order: global provider headers, then these
     * service headers, then per-request additional headers.
     */
    val serviceHeaders: List<Pair<String, String>>

    companion object {
        /**
         * Creates a [Builder] for constructing a [ServerProcessingConfig] with a fluent API.
         *
         * Example:
         * ```
         * val config = ServerProcessingConfig.builder<MyModel>()
         *     .fetchWithGet("https://api.example.com/items", syncCadenceSeconds = 300) { response ->
         *         // transform response to List<MyModel>
         *     }
         *     .syncUp { requestTag, responseBody ->
         *         // return SyncUpResult
         *     }
         *     .serviceHeaders("Content-Type" to "application/json")
         *     .build()
         * ```
         */
        fun <O : SyncableObject<O>> builder(): Builder<O> = Builder()
    }

    class Builder<O : SyncableObject<O>> internal constructor() {
        private var fetchConfig: SyncFetchConfig<O>? = null
        private var upConfig: SyncUpConfig<O>? = null
        private var headers: List<Pair<String, String>> = emptyList()

        /**
         * Configures sync-down using a GET request.
         *
         * @param endpoint the URL to fetch from.
         * @param syncCadenceSeconds how often (in seconds) to sync down.
         * @param transformResponse extracts a list of [O] from the raw JSON response.
         */
        fun fetchWithGet(
            endpoint: String,
            syncCadenceSeconds: Long,
            transformResponse: (JsonObject) -> List<O>,
        ): Builder<O> = apply {
            fetchConfig = SyncFetchConfig.GetFetchConfig(endpoint, syncCadenceSeconds, transformResponse)
        }

        /**
         * Configures sync-down using a POST request.
         *
         * @param endpoint the URL to fetch from.
         * @param requestBody the POST request body.
         * @param syncCadenceSeconds how often (in seconds) to sync down.
         * @param transformResponse extracts a list of [O] from the raw JSON response.
         */
        fun fetchWithPost(
            endpoint: String,
            requestBody: JsonObject,
            syncCadenceSeconds: Long,
            transformResponse: (JsonObject) -> List<O>,
        ): Builder<O> = apply {
            fetchConfig = SyncFetchConfig.PostFetchConfig(endpoint, requestBody, syncCadenceSeconds, transformResponse)
        }

        /**
         * Configures sync-up response parsing.
         *
         * @param fromResponseBody a function that extracts [O] from a sync-up response,
         *  given the request tag and raw JSON response body.
         */
        fun syncUp(
            fromResponseBody: (requestTag: String, responseBody: JsonObject) -> SyncUpResult<O>,
        ): Builder<O> = apply {
            upConfig = object : SyncUpConfig<O>() {
                override fun fromResponseBody(requestTag: String, responseBody: JsonObject): SyncUpResult<O> =
                    fromResponseBody(requestTag, responseBody)
            }
        }

        /**
         * Configures sync-up by delegating to an existing [ResponseUnpacker], so you can
         * define your response parsing logic once and reuse it for both synchronous operations
         * and async sync-up.
         *
         * @param unpacker the [ResponseUnpacker] to delegate to.
         */
        fun syncUpFromUnpacker(unpacker: ResponseUnpacker<O>): Builder<O> = apply {
            upConfig = SyncUpConfig.fromUnpacker(unpacker)
        }

        /**
         * Sets per-service headers applied to every request this service makes.
         */
        fun serviceHeaders(vararg headers: Pair<String, String>): Builder<O> = apply {
            this.headers = headers.toList()
        }

        /**
         * Builds the [ServerProcessingConfig]. Requires both [fetchWithGet]/[fetchWithPost]
         * and [syncUp]/[syncUpFromUnpacker] to have been called.
         *
         * @throws IllegalStateException if fetch or sync-up config is missing.
         */
        fun build(): ServerProcessingConfig<O> {
            val fetch = requireNotNull(fetchConfig) {
                "ServerProcessingConfig.Builder requires a fetch config. Call fetchWithGet() or fetchWithPost()."
            }
            val up = requireNotNull(upConfig) {
                "ServerProcessingConfig.Builder requires a sync-up config. Call syncUp() or syncUpFromUnpacker()."
            }
            return object : ServerProcessingConfig<O> {
                override val syncFetchConfig: SyncFetchConfig<O> = fetch
                override val syncUpConfig: SyncUpConfig<O> = up
                override val serviceHeaders: List<Pair<String, String>> = headers
            }
        }
    }
}
