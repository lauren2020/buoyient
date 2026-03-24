package com.les.databuoy.examples.todo

import com.les.databuoy.SyncableObject
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.UUID

@Serializable
data class Todo(
    @SerialName("server_id") override val serverId: String? = null,
    @SerialName("client_id") override val clientId: String = UUID.randomUUID().toString(),
    override val version: Int = 0,
    @Transient override val syncStatus: SyncableObject.SyncStatus = SyncableObject.SyncStatus.LocalOnly,
    val title: String,
    val completed: Boolean = false,
) : SyncableObject<Todo> {

    override fun withSyncStatus(syncStatus: SyncableObject.SyncStatus): Todo =
        copy(syncStatus = syncStatus)
}
