package com.example.sync

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Shared serialization helper that encodes/decodes [SyncableObject] instances
 * using `kotlinx.serialization`. A single instance is created per service and
 * threaded through the internal classes, replacing per-class [Json] instances
 * and duplicate encode/decode helpers.
 */
class SyncCodec<O : SyncableObject<O>>(
    val serializer: KSerializer<O>,
) {
    val json = Json { ignoreUnknownKeys = true }

    fun encode(obj: O): JsonObject =
        json.encodeToJsonElement(serializer, obj).jsonObject

    fun encodeToString(obj: O): String =
        encode(obj).toString()

    fun decode(jsonObject: JsonObject, syncStatus: SyncableObject.SyncStatus): O =
        json.decodeFromJsonElement(serializer, jsonObject).withSyncStatus(syncStatus)

    fun decode(jsonString: String, syncStatus: SyncableObject.SyncStatus): O =
        json.decodeFromString(serializer, jsonString).withSyncStatus(syncStatus)
}
