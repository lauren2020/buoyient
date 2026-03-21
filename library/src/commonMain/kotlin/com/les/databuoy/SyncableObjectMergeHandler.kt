package com.les.databuoy

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Encapsulates the 3-way merge logic used during [SyncableObjectService.syncDownFromServer]
 * to reconcile local and server changes.
 *
 * This is an `open class` so that service implementations can subclass it to override
 * [handleMergeConflict] and/or [mergeServerAndLocalChanges] with custom merge policies.
 *
 * Provide a custom subclass to [SyncableObjectService] by overriding its
 * `mergeHandler` property.
 */
open class SyncableObjectMergeHandler<O : SyncableObject<O>>(
    private val codec: SyncCodec<O>,
) {

    // region Types

    sealed class ConflictResolution<O> {
        /** The conflict was resolved — use [resolvedData] as the merged data. */
        class Resolved<O>(
            val resolvedData: O,
            val updatedHttpRequest: HttpRequest,
        ) : ConflictResolution<O>()

        /** The conflict is unresolved — mark the row as CONFLICT for manual resolution. */
        class Unresolved<O> : ConflictResolution<O>()
    }

    sealed class MergeResult<O : SyncableObject<O>> {

        class Conflict<O : SyncableObject<O>>(
            val conflict: FieldConflict<O>,
        ) : MergeResult<O>()

        class Merged<O : SyncableObject<O>>(
            val mergedData: O,
            val updatedHttpRequest: HttpRequest?,
        ) : MergeResult<O>()
    }

    data class FieldConflict<O : SyncableObject<O>>(
        val pendingRequestId: Int,
        val fieldNames: List<String>,
        val baseValue: O?,
        val localValue: O,
        val serverValue: O,
        val requestTag: String? = null,
    ) {
        fun toJson(codec: SyncCodec<O>): JsonObject = buildJsonObject {
            put(PENDING_REQUEST_ID_KEY, JsonPrimitive(pendingRequestId))
            put(FIELD_NAMES_KEY, JsonPrimitive(fieldNames.joinToString(":")))
            put(BASE_DATA_KEY, baseValue?.let { codec.encode(it) } ?: JsonObject(emptyMap()))
            put(LOCAL_DATA_KEY, codec.encode(localValue))
            put(SERVER_DATA_KEY, codec.encode(serverValue))
            requestTag?.let { put(REQUEST_TAG_KEY, JsonPrimitive(it)) }
        }

        companion object {
            const val PENDING_REQUEST_ID_KEY = "pending_request_id"
            const val FIELD_NAMES_KEY = "field_names"
            const val BASE_DATA_KEY = "base_data"
            const val LOCAL_DATA_KEY = "local_data"
            const val SERVER_DATA_KEY = "server_data"
            const val REQUEST_TAG_KEY = "request_tag"

            fun <O : SyncableObject<O>> fromJson(
                jsonObject: JsonObject,
                codec: SyncCodec<O>,
            ): FieldConflict<O> {
                val localOnlyStatus = SyncableObject.SyncStatus.LocalOnly
                return FieldConflict(
                    pendingRequestId = jsonObject[PENDING_REQUEST_ID_KEY]!!.jsonPrimitive.int,
                    fieldNames = jsonObject[FIELD_NAMES_KEY]!!.jsonPrimitive.content.split(":"),
                    baseValue = jsonObject[BASE_DATA_KEY]?.jsonObject?.let { codec.decode(it, localOnlyStatus) },
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
     * Called during [SyncableObjectService.syncDownFromServer] when a 3-way merge detects
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
    open fun handleMergeConflict(
        mergeResult: MergeResult<O>,
        requestTag: String? = null,
    ): ConflictResolution<O> = ConflictResolution.Unresolved()

    /**
     * Performs a 3-way comparison of [base], [local], and [server] JSON objects.
     *
     * This function is open so that subclasses can override it if they need
     * custom merge logic beyond simple field comparison.
     *
     * @param baseData - the last synced data from the server which the local changes have
     * been built on top of.
     * @param localData - the version containing all local changes made by this client
     * on top of the base state.
     * @param serverData - the latest version from the server.
     * @param pendingHttpRequest - the pending sync request associated with the local changes.
     * @param pendingRequestId - the id of the pending change row in the db.
     *
     * Returns a [MergeResult] containing:
     * - [MergeResult.mergedJson]: starts from [local] with server-only changes applied
     * - [MergeResult.conflicts]: fields changed by both local and server to different values
     * - [MergeResult.serverOnlyChanges]: field names changed only on the server
     * - [MergeResult.localOnlyChanges]: field names changed only locally
     */
    open fun mergeServerAndLocalChanges(
        baseData: O?,
        localData: O,
        serverData: O,
        pendingHttpRequest: HttpRequest,
        pendingRequestId: Int,
        requestTag: String?,
    ): MergeResult<O> {
        val base = baseData?.let { codec.encode(it) } ?: JsonObject(emptyMap())
        val local = codec.encode(localData)
        val server = codec.encode(serverData)
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
                    // TRUE CONFLICT — both changed to different values, keep local
                    conflicts.add(key)
                }
            }
        }
        val merged = JsonObject(mergedMap)
        return if (conflicts.isEmpty()) {
            MergeResult.Merged(
                mergedData = codec.decode(merged, localData.syncStatus),
                updatedHttpRequest = null,
            )
        } else {
            MergeResult.Conflict(
                conflict = FieldConflict(
                    pendingRequestId = pendingRequestId,
                    fieldNames = conflicts,
                    baseValue = baseData,
                    localValue = localData,
                    serverValue = serverData,
                    requestTag = requestTag,
                ),
            )
        }
    }

    // endregion
}
