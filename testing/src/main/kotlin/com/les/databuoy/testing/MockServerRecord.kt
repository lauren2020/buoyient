package com.les.databuoy.testing

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A single record in a [MockServerCollection], representing server-side state.
 *
 * This is a generic JSON-based record — it does not know about client-side
 * `SyncableObject` types. The [data] field holds the domain-specific fields
 * (e.g. `title`, `completed`), while [serverId], [version], and [voided] are
 * managed by the store itself.
 *
 * @property serverId the server-assigned unique identifier.
 * @property clientId the client-assigned reference ID, if the client included it in the request body.
 * @property version the current version number. Starts at 1 on create, increments on each update/void.
 * @property data the record's domain fields as a JSON object.
 * @property voided whether the record has been soft-deleted.
 * @property createdAt epoch seconds when the record was created.
 * @property updatedAt epoch seconds when the record was last modified. Used by
 *   [MockServerCollection.getUpdatedSince] for delta sync-down filtering.
 */
public data class MockServerRecord(
    public val serverId: String,
    public val clientId: String?,
    public val version: Int,
    public val data: JsonObject,
    public val voided: Boolean = false,
    public val createdAt: Long,
    public val updatedAt: Long,
)

/**
 * Converts this record to a flat [JsonObject] by merging [MockServerRecord.data] fields
 * with the record's metadata (`server_id`, `client_id`, `version`, `voided`).
 *
 * The resulting shape is what a typical REST API would return:
 * ```json
 * {
 *   "server_id": "server-1",
 *   "client_id": "test-id-1",
 *   "version": 2,
 *   "voided": false,
 *   "title": "Buy milk",
 *   "completed": true
 * }
 * ```
 */
public fun MockServerRecord.toJsonObject(): JsonObject = buildJsonObject {
    put("server_id", serverId)
    clientId?.let { put("client_id", it) }
    put("version", version)
    put("voided", voided)
    data.forEach { (key, value) -> put(key, value) }
}
