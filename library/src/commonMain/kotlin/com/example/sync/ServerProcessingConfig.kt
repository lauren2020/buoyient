package com.example.sync

import kotlinx.serialization.json.JsonObject

interface ServerProcessingConfig<T> {
    val syncFetchConfig: SyncFetchConfig<T>
    val syncUpConfig: SyncUpConfig
    val headers: List<Pair<String, String>>
    fun fromServerProtoJson(json: JsonObject): T
}