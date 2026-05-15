package com.elvdev.buoyient.paging

import com.elvdev.buoyient.ServiceRequestTag
import com.elvdev.buoyient.SyncableObject
import com.elvdev.buoyient.SyncableObjectService
import com.elvdev.buoyient.datatypes.CreateRequestBuilder
import com.elvdev.buoyient.datatypes.HttpRequest
import com.elvdev.buoyient.datatypes.ResponseUnpacker
import com.elvdev.buoyient.serviceconfigs.ConnectivityChecker
import com.elvdev.buoyient.serviceconfigs.PagingConfig
import com.elvdev.buoyient.serviceconfigs.ServerProcessingConfig
import com.elvdev.buoyient.serviceconfigs.SyncFetchConfig
import com.elvdev.buoyient.serviceconfigs.SyncUpConfig
import com.elvdev.buoyient.serviceconfigs.SyncUpResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Test-only fixtures mirroring those in `:syncable-objects`'s jvmTest source set.
 * Duplicated here because the JVM test source sets are not visible across modules.
 * Keep this lean — only what `:paging` tests need.
 */

@Serializable
data class TestItem(
    @SerialName("server_id") override val serverId: String? = null,
    @SerialName("client_id") override val clientId: String,
    override val version: String? = null,
    @Transient override val syncStatus: SyncableObject.SyncStatus = SyncableObject.SyncStatus.LocalOnly,
    val name: String,
    val value: Int = 0,
) : SyncableObject<TestItem> {
    override fun withSyncStatus(syncStatus: SyncableObject.SyncStatus) = copy(syncStatus = syncStatus)
}

enum class TestRequestTag(override val value: String) : ServiceRequestTag {
    DEFAULT("default"),
}

fun testItem(clientId: String, name: String, value: Int = 0): TestItem =
    TestItem(clientId = clientId, name = name, value = value)

fun testServerConfig() = object : ServerProcessingConfig<TestItem> {
    override val syncFetchConfig = SyncFetchConfig.GetFetchConfig<TestItem>(
        endpoint = "https://api.test.com/items",
        syncCadenceSeconds = 999_999,
        transformResponse = { emptyList() },
    )
    override val syncUpConfig = object : SyncUpConfig<TestItem>() {
        override fun fromResponseBody(requestTag: String, responseBody: JsonObject): SyncUpResult<TestItem> {
            val data = responseBody["data"]?.jsonObject ?: return SyncUpResult.Failed.RemovePendingRequest()
            return SyncUpResult.Success(
                Json.decodeFromJsonElement(TestItem.serializer(), data)
                    .withSyncStatus(SyncableObject.SyncStatus.Synced(""))
            )
        }
    }
    override val serviceHeaders: List<Pair<String, String>> = emptyList()
}

class TestItemService(
    serverProcessingConfig: ServerProcessingConfig<TestItem> = testServerConfig(),
    connectivityChecker: ConnectivityChecker,
    pagingConfig: PagingConfig<TestItem> = PagingConfig(keyExtractor = { it.name }, sortOrder = PagingConfig.SortOrder.ASC),
    indexedJsonPaths: List<String> = emptyList(),
) : SyncableObjectService<TestItem, TestRequestTag>(
    serializer = TestItem.serializer(),
    serverProcessingConfig = serverProcessingConfig,
    serviceName = "test-items",
    connectivityChecker = connectivityChecker,
    pagingConfig = pagingConfig,
    indexedJsonPaths = indexedJsonPaths,
) {
    init { stopPeriodicSyncDown() }

    suspend fun testCreate(item: TestItem) = create(
        data = item,
        requestTag = TestRequestTag.DEFAULT,
        request = CreateRequestBuilder { data, idempotencyKey, _, _ ->
            HttpRequest(
                HttpRequest.HttpMethod.POST, "https://api.test.com/items",
                buildJsonObject {
                    put("client_id", data.clientId)
                    put("name", data.name)
                    put("idempotency_key", idempotencyKey)
                },
            )
        },
        unpackSyncData = ResponseUnpacker { body, _, syncStatus ->
            body["data"]?.jsonObject?.let {
                Json.decodeFromJsonElement(TestItem.serializer(), it).withSyncStatus(syncStatus)
            }
        },
    )
}
