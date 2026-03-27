package com.elvdev.buoyient

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Nested line-item model used by [TestOrder].
 *
 * Simulates a sub-object that gains a server-assigned [serverId] after the
 * parent is created on the server.
 */
@Serializable
data class LineItem(
    val name: String,
    val quantity: Int = 1,
    @SerialName("server_id") val serverId: String? = null,
)

/**
 * [SyncableObject] with a list of complex nested objects ([LineItem]).
 *
 * Used to test the 3-way merge when the server enriches nested array
 * elements (e.g., assigns IDs to line items after a create).
 */
@Serializable
data class TestOrder(
    @SerialName("server_id") override val serverId: String? = null,
    @SerialName("client_id") override val clientId: String,
    override val version: String? = null,
    @Transient override val syncStatus: SyncableObject.SyncStatus = SyncableObject.SyncStatus.LocalOnly,
    val name: String,
    val items: List<LineItem> = emptyList(),
) : SyncableObject<TestOrder> {
    override fun withSyncStatus(syncStatus: SyncableObject.SyncStatus) = copy(syncStatus = syncStatus)
}
