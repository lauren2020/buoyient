package com.les.databuoy.utils

import com.les.databuoy.SyncableObject
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Shared serialization helper that encodes/decodes [com.les.databuoy.SyncableObject] instances
 * using `kotlinx.serialization`. A single instance is created per service and
 * threaded through the internal classes, replacing per-class [kotlinx.serialization.json.Json] instances
 * and duplicate encode/decode helpers.
 */
public class SyncCodec<O : SyncableObject<O>>(
    public val serializer: KSerializer<O>,
) {
    public val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    public fun encode(obj: O): JsonObject =
        json.encodeToJsonElement(serializer, obj).jsonObject

    public fun encodeToString(obj: O): String =
        encode(obj).toString()

    public fun decode(jsonObject: JsonObject, syncStatus: SyncableObject.SyncStatus): O =
        json.decodeFromJsonElement(serializer, jsonObject).withSyncStatus(syncStatus)

    public fun decode(jsonString: String, syncStatus: SyncableObject.SyncStatus): O =
        json.decodeFromString(serializer, jsonString).withSyncStatus(syncStatus)
}