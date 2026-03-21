package com.les.databuoy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Minimal [SyncableObject] implementation for unit tests.
 *
 * [syncStatus] is marked @Transient so it is excluded from JSON —
 * it is framework metadata, not domain data, and including it would
 * cause false 3-way-merge conflicts.
 */
@Serializable
data class TestItem(
    @SerialName("server_id") override val serverId: String? = null,
    @SerialName("client_id") override val clientId: String,
    override val version: Int,
    @Transient override val syncStatus: SyncableObject.SyncStatus = SyncableObject.SyncStatus.LocalOnly,
    val name: String,
    val value: Int,
) : SyncableObject<TestItem> {

    override fun withSyncStatus(syncStatus: SyncableObject.SyncStatus) = copy(syncStatus = syncStatus)
}
