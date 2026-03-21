package com.example.sync.testing

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * A named collection of [MockServerRecord]s within a [MockServerStore].
 *
 * This class serves two audiences:
 *
 * 1. **Mock HTTP handlers** call [create], [update], [get], [getAll], [getUpdatedSince],
 *    and [void] to implement realistic CRUD behavior backed by persistent state.
 *
 * 2. **Test setup code** calls [seed], [mutate], [remove], [findByClientId], [clear],
 *    etc. to prepare server-side state or simulate actions by another client.
 *
 * All operations are thread-safe via [ConcurrentHashMap].
 *
 * @property name the collection name (typically matches the service name, e.g. "todos").
 */
class MockServerCollection internal constructor(
    val name: String,
    private val records: ConcurrentHashMap<String, MockServerRecord>,
    private val generateId: () -> String,
    private val clock: () -> Long,
) {

    // -------------------------------------------------------------------------
    // CRUD — called by auto-registered mock handlers
    // -------------------------------------------------------------------------

    /**
     * Creates a new record from a client request body.
     *
     * Assigns a generated [MockServerRecord.serverId], sets version to 1,
     * and extracts `client_id` / `reference_id` from the body if present.
     *
     * @return the newly created record.
     */
    fun create(requestBody: JsonObject): MockServerRecord {
        val serverId = generateId()
        val now = clock()
        val clientId = requestBody["client_id"]?.jsonPrimitive?.contentOrNull
            ?: requestBody["reference_id"]?.jsonPrimitive?.contentOrNull
        val record = MockServerRecord(
            serverId = serverId,
            clientId = clientId,
            version = 1,
            data = requestBody,
            voided = false,
            createdAt = now,
            updatedAt = now,
        )
        records[serverId] = record
        return record
    }

    /**
     * Updates an existing record by shallow-merging [requestBody] fields into the
     * current data. Keys present in [requestBody] overwrite existing values; keys
     * not in [requestBody] are preserved. Version is incremented by 1.
     *
     * @return the updated record, or `null` if [serverId] is not found.
     */
    fun update(serverId: String, requestBody: JsonObject): MockServerRecord? {
        var result: MockServerRecord? = null
        records.computeIfPresent(serverId) { _, existing ->
            val mergedData = buildJsonObject {
                existing.data.forEach { (k, v) -> put(k, v) }
                requestBody.forEach { (k, v) -> put(k, v) }
            }
            val updated = existing.copy(
                version = existing.version + 1,
                data = mergedData,
                updatedAt = clock(),
            )
            result = updated
            updated
        }
        return result
    }

    /**
     * Retrieves a record by server ID.
     */
    fun get(serverId: String): MockServerRecord? = records[serverId]

    /**
     * Returns all records in this collection, including voided ones.
     */
    fun getAll(): List<MockServerRecord> = records.values.toList()

    /**
     * Returns records where [MockServerRecord.updatedAt] is strictly greater than
     * [epochSeconds]. Use this to implement delta sync-down endpoints.
     */
    fun getUpdatedSince(epochSeconds: Long): List<MockServerRecord> =
        records.values.filter { it.updatedAt > epochSeconds }

    /**
     * Marks a record as voided (soft-delete). Increments version and updates timestamp.
     *
     * @return the voided record, or `null` if [serverId] is not found.
     */
    fun void(serverId: String): MockServerRecord? {
        var result: MockServerRecord? = null
        records.computeIfPresent(serverId) { _, existing ->
            val updated = existing.copy(
                voided = true,
                version = existing.version + 1,
                updatedAt = clock(),
            )
            result = updated
            updated
        }
        return result
    }

    // -------------------------------------------------------------------------
    // Test Setup API — called directly by test code
    // -------------------------------------------------------------------------

    /**
     * Seeds a record with explicit field values. Unlike [create], this does not
     * auto-generate a server ID or set version to 1 — you control everything.
     *
     * @param serverId the server ID to assign.
     * @param data the record's domain fields.
     * @param version the version number (defaults to 1).
     * @param clientId optional client ID for correlation.
     * @param voided whether the record is voided (defaults to false).
     * @return the seeded record.
     */
    fun seed(
        serverId: String,
        data: JsonObject,
        version: Int = 1,
        clientId: String? = null,
        voided: Boolean = false,
    ): MockServerRecord {
        val now = clock()
        val record = MockServerRecord(
            serverId = serverId,
            clientId = clientId,
            version = version,
            data = data,
            voided = voided,
            createdAt = now,
            updatedAt = now,
        )
        records[serverId] = record
        return record
    }

    /**
     * Seeds a pre-built record directly.
     */
    fun seed(record: MockServerRecord): MockServerRecord {
        records[record.serverId] = record
        return record
    }

    /**
     * Reads the current record, applies [transform] to its data, increments the
     * version, and updates the timestamp. This simulates "another client updated
     * the server" — the key method for divergence and conflict testing.
     *
     * @return the mutated record, or `null` if [serverId] is not found.
     */
    fun mutate(serverId: String, transform: (JsonObject) -> JsonObject): MockServerRecord? {
        var result: MockServerRecord? = null
        records.computeIfPresent(serverId) { _, existing ->
            val updated = existing.copy(
                data = transform(existing.data),
                version = existing.version + 1,
                updatedAt = clock(),
            )
            result = updated
            updated
        }
        return result
    }

    /**
     * Hard-deletes a record from the collection. Unlike [void], this removes
     * the record entirely — it will not appear in [getAll] or [getUpdatedSince].
     *
     * @return `true` if the record existed and was removed.
     */
    fun remove(serverId: String): Boolean = records.remove(serverId) != null

    /**
     * Finds a record by its client-assigned ID.
     *
     * @return the matching record, or `null` if not found.
     */
    fun findByClientId(clientId: String): MockServerRecord? =
        records.values.find { it.clientId == clientId }

    /** Returns the number of records in this collection. */
    fun count(): Int = records.size

    /** Returns `true` if this collection contains no records. */
    fun isEmpty(): Boolean = records.isEmpty()

    /** Removes all records from this collection. */
    fun clear() {
        records.clear()
    }
}
