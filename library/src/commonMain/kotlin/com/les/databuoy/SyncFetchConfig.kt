package com.les.databuoy

import kotlinx.serialization.json.JsonObject

sealed class SyncFetchConfig<out T>(
    private val transformResponse: (JsonObject) -> List<T>,
) {
    abstract val syncCadenceSeconds: Long
    fun transformItemsListFromResponse(response: JsonObject): List<T> = transformResponse(response)

    class GetFetchConfig<T>(
        val endpoint: String,
        override val syncCadenceSeconds: Long,
        transformResponse: (JsonObject) -> List<T>,
    ) : SyncFetchConfig<T>(transformResponse)

    class PostFetchConfig<T>(
        val endpoint: String,
        val requestBody: JsonObject,
        override val syncCadenceSeconds: Long,
        transformResponse: (JsonObject) -> List<T>,
    ) : SyncFetchConfig<T>(transformResponse)
}
