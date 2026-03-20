package com.example.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Minimal [SyncableObject] implementation for unit tests.
 *
 * [toJson] intentionally excludes [syncStatus] — it is framework metadata, not
 * domain data, and including it would cause false 3-way-merge conflicts.
 */
data class TestItem(
    override val serverId: String?,
    override val clientId: String,
    override val version: Int,
    override val syncStatus: SyncableObject.SyncStatus,
    val name: String,
    val value: Int,
) : SyncableObject<TestItem> {

    override fun toJson(): JsonObject = buildJsonObject {
        serverId?.let { put("server_id", it) }
        put("client_id", clientId)
        put("version", version)
        put("name", name)
        put("value", value)
    }

    object Deserializer : SyncableObject.SyncableObjectDeserializer<TestItem> {
        override fun fromJson(
            json: JsonObject,
            syncStatus: SyncableObject.SyncStatus,
        ): TestItem = TestItem(
            serverId = json["server_id"]?.jsonPrimitive?.content?.ifEmpty { null },
            clientId = json["client_id"]!!.jsonPrimitive.content,
            version = json["version"]!!.jsonPrimitive.int,
            syncStatus = syncStatus,
            name = json["name"]!!.jsonPrimitive.content,
            value = json["value"]!!.jsonPrimitive.int,
        )
    }
}
