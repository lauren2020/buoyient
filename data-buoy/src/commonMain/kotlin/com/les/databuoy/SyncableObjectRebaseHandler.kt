package com.les.databuoy

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Encapsulates the 3-way merge logic used during [SyncDriver.syncDownFromServer]
 * to reconcile local and server changes.
 *
 * This is an `open class` so that service implementations can subclass it to override
 * [handleMergeConflict] and/or [rebaseDataForPendingRequest] with custom merge policies.
 *
 * Provide a custom subclass to [SyncableObjectService] by overriding its
 * `mergeHandler` property.
 */
public open class SyncableObjectRebaseHandler<O : SyncableObject<O>>(
    private val codec: SyncCodec<O>,
) {

    // region Types

    public sealed class ConflictResolution<O> {
        /** The conflict was resolved — use [resolvedData] as the merged data. */
        public class Resolved<O>(
            public val resolvedData: O,
            public val updatedHttpRequest: HttpRequest?,
        ) : ConflictResolution<O>()

        /** The conflict is unresolved — mark the row as CONFLICT for manual resolution. */
        public class Unresolved<O> : ConflictResolution<O>()
    }

    public sealed class RebaseResult<O : SyncableObject<O>> {

        public class Conflict<O : SyncableObject<O>>(
            public val conflict: FieldConflict<O>,
        ) : RebaseResult<O>()

        public class Rebased<O : SyncableObject<O>>(
            public val mergedData: O,
            public val updatedHttpRequest: HttpRequest?,
        ) : RebaseResult<O>()
    }

    public data class FieldConflict<O : SyncableObject<O>>(
        public val pendingRequestId: Int,
        public val fieldNames: List<String>,
        public val baseValue: O?,
        public val localValue: O,
        public val serverValue: O,
        public val requestTag: String? = null,
    ) {
        public fun toJson(codec: SyncCodec<O>): JsonObject = buildJsonObject {
            put(PENDING_REQUEST_ID_KEY, JsonPrimitive(pendingRequestId))
            put(FIELD_NAMES_KEY, JsonPrimitive(fieldNames.joinToString(":")))
            put(BASE_DATA_KEY, baseValue?.let { codec.encode(it) } ?: JsonObject(emptyMap()))
            put(LOCAL_DATA_KEY, codec.encode(localValue))
            put(SERVER_DATA_KEY, codec.encode(serverValue))
            requestTag?.let { put(REQUEST_TAG_KEY, JsonPrimitive(it)) }
        }

        public companion object {
            public const val PENDING_REQUEST_ID_KEY: String = "pending_request_id"
            public const val FIELD_NAMES_KEY: String = "field_names"
            public const val BASE_DATA_KEY: String = "base_data"
            public const val LOCAL_DATA_KEY: String = "local_data"
            public const val SERVER_DATA_KEY: String = "server_data"
            public const val REQUEST_TAG_KEY: String = "request_tag"

            public fun <O : SyncableObject<O>> fromJson(
                jsonObject: JsonObject,
                codec: SyncCodec<O>,
            ): FieldConflict<O> {
                val localOnlyStatus = SyncableObject.SyncStatus.LocalOnly
                return FieldConflict(
                    pendingRequestId = jsonObject[PENDING_REQUEST_ID_KEY]!!.jsonPrimitive.int,
                    fieldNames = jsonObject[FIELD_NAMES_KEY]!!.jsonPrimitive.content.split(":"),
                    baseValue = jsonObject[BASE_DATA_KEY]?.jsonObject?.takeIf { it.isNotEmpty() }?.let {
                        codec.decode(it, localOnlyStatus)
                    },
                    localValue = codec.decode(jsonObject[LOCAL_DATA_KEY]!!.jsonObject, localOnlyStatus),
                    serverValue = codec.decode(jsonObject[SERVER_DATA_KEY]!!.jsonObject, localOnlyStatus),
                    requestTag = jsonObject[REQUEST_TAG_KEY]?.jsonPrimitive?.content,
                )
            }
        }
    }

    // endregion

    // region Logic

    /**
     * Called during [SyncDriver.syncDownFromServer] when a 3-way merge detects
     * field-level conflicts (both the local client and the server changed the same field
     * to different values).
     *
     * Override this in a subclass to apply custom resolution rules (e.g., per-field policies
     * like "status always takes server value"). Return [ConflictResolution.Resolved] with a
     * manually merged JSON and an updated request body to resolve the conflict, or
     * [ConflictResolution.Unresolved] to mark the row as CONFLICT for later manual resolution.
     *
     * The default implementation returns [ConflictResolution.Unresolved].
     */
    public open fun handleMergeConflict(
        rebaseResult: RebaseResult<O>,
        requestTag: String? = null,
    ): ConflictResolution<O> = ConflictResolution.Unresolved()

    /**
     * Performs a 3-way comparison of [base], [local], and [server] JSON objects.
     *
     * This function is open so that subclasses can override it if they need
     * custom merge logic beyond simple field comparison.
     *
     * @param oldBaseData - the last synced data from the server which the local changes have
     * been built on top of.
     * @param currentData - the version containing all local changes made by this client
     * on top of the base state.
     * @param newBaseData - the latest version from the server.
     * @param pendingHttpRequest - the pending sync request associated with the local changes.
     * @param pendingRequestId - the id of the pending change row in the db.
     *
     * Returns a [RebaseResult] containing:
     * - [RebaseResult.mergedJson]: starts from [local] with server-only changes applied
     * - [RebaseResult.conflicts]: fields changed by both local and server to different values
     * - [RebaseResult.serverOnlyChanges]: field names changed only on the server
     * - [RebaseResult.localOnlyChanges]: field names changed only locally
     */
    public open fun rebaseDataForPendingRequest(
        oldBaseData: O?,
        currentData: O,
        newBaseData: O,
        pendingHttpRequest: HttpRequest,
        pendingRequestId: Int,
        requestTag: String,
    ): RebaseResult<O> {
        val base = oldBaseData?.let { codec.encode(it) } ?: JsonObject(emptyMap())
        val local = codec.encode(currentData)
        val server = codec.encode(newBaseData)
        val allKeys = (base.keys + local.keys + server.keys).toSet()
        val mergedMap = local.toMutableMap()
        val conflicts = mutableListOf<String>()
        val serverOnlyChanges = mutableListOf<String>()
        val localOnlyChanges = mutableListOf<String>()

        for (key in allKeys) {
            val baseVal = base[key]
            val localVal = local[key]
            val serverVal = server[key]

            val baseStr = baseVal?.toString()
            val localStr = localVal?.toString()
            val serverStr = serverVal?.toString()

            val baseEqLocal = baseStr == localStr
            val baseEqServer = baseStr == serverStr

            when {
                baseEqLocal && baseEqServer -> { /* no change anywhere */ }
                baseEqLocal && !baseEqServer -> {
                    // Server changed, local didn't → take server value
                    serverOnlyChanges.add(key)
                    if (serverVal != null) {
                        mergedMap[key] = serverVal
                    } else {
                        mergedMap.remove(key)
                    }
                }
                !baseEqLocal && baseEqServer -> {
                    // Local changed, server didn't → keep local (already in merged)
                    localOnlyChanges.add(key)
                }
                localStr == serverStr -> {
                    // Both changed to the same value → no conflict
                }
                else -> {
                    // Both changed to different values.
                    // If the field is an array and both sides only added elements
                    // (i.e. both are supersets of the base), merge the additions
                    // instead of treating it as a conflict.
                    val mergedArray = tryMergeArrayAdditions(baseVal, localVal, serverVal)
                    if (mergedArray != null) {
                        mergedMap[key] = mergedArray
                    } else {
                        conflicts.add(key)
                    }
                }
            }
        }
        val merged = JsonObject(mergedMap)
        return if (conflicts.isEmpty()) {
            RebaseResult.Rebased(
                mergedData = codec.decode(merged, currentData.syncStatus),
                updatedHttpRequest = null,
            )
        } else {
            RebaseResult.Conflict(
                conflict = FieldConflict(
                    pendingRequestId = pendingRequestId,
                    fieldNames = conflicts,
                    baseValue = oldBaseData,
                    localValue = currentData,
                    serverValue = newBaseData,
                    requestTag = requestTag,
                ),
            )
        }
    }

    /**
     * If [baseVal], [localVal], and [serverVal] are all [JsonArray]s (or base is null)
     * and both local and server are strict supersets of base (only additions, no removals),
     * returns a merged array containing base elements + local additions + server additions.
     *
     * Returns `null` if the values are not arrays or if either side removed elements.
     */
    private fun tryMergeArrayAdditions(
        baseVal: kotlinx.serialization.json.JsonElement?,
        localVal: kotlinx.serialization.json.JsonElement?,
        serverVal: kotlinx.serialization.json.JsonElement?,
    ): JsonArray? {
        val localArray = localVal as? JsonArray ?: return null
        val serverArray = serverVal as? JsonArray ?: return null
        val baseArray = (baseVal as? JsonArray) ?: JsonArray(emptyList())

        val baseElements = baseArray.toSet()

        // Both sides must contain every base element (no removals).
        if (!localArray.toSet().containsAll(baseElements)) return null
        if (!serverArray.toSet().containsAll(baseElements)) return null

        val localAdditions = localArray.filter { it !in baseElements }
        val serverAdditions = serverArray.filter { it !in baseElements }

        // Preserve order: base elements (in local order) + server-only additions.
        val merged = localArray + serverAdditions.filter { it !in localArray.toSet() }
        return JsonArray(merged)
    }

    // endregion
}
