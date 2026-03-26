package com.les.databuoy.serviceconfigs

import kotlinx.serialization.json.JsonObject

public sealed class SyncFetchConfig<out T>(
    private val transformResponse: (JsonObject) -> List<T>,
) {
    public abstract val syncCadenceSeconds: Long
    public fun transformItemsListFromResponse(response: JsonObject): List<T> = transformResponse(response)

    public class GetFetchConfig<T>(
        public val endpoint: String,
        override val syncCadenceSeconds: Long,
        transformResponse: (JsonObject) -> List<T>,
    ) : SyncFetchConfig<T>(transformResponse)

    public class PostFetchConfig<T>(
        public val endpoint: String,
        public val requestBody: JsonObject,
        override val syncCadenceSeconds: Long,
        transformResponse: (JsonObject) -> List<T>,
    ) : SyncFetchConfig<T>(transformResponse)
}