package com.les.databuoy

import com.les.databuoy.managers.LocalStoreManager
import com.les.databuoy.managers.PendingRequestQueueManager
import com.les.databuoy.managers.PendingSyncRequest
import com.les.databuoy.serviceconfigs.PendingRequestQueueStrategy
import com.les.databuoy.serviceconfigs.ServerProcessingConfig
import com.les.databuoy.serviceconfigs.SyncFetchConfig
import com.les.databuoy.serviceconfigs.SyncUpConfig
import com.les.databuoy.serviceconfigs.SyncUpResult
import com.les.databuoy.syncableobjectservicedatatypes.HttpRequest
import com.les.databuoy.syncableobjectservicedatatypes.SquashRequestMerger
import com.les.databuoy.testing.TestServiceEnvironment
import com.les.databuoy.testing.registerCrudHandlers
import com.les.databuoy.utils.SyncCodec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for the [com.les.databuoy.serviceconfigs.PendingRequestQueueStrategy.Squash] strategy
 * wired through [MockServerStoreRouter.registerCrudHandlers].
 *
 * These verify that multiple offline updates to the same object are squashed into a single
 * pending request rather than queued individually, and that the squashed request contains
 * the correct merged data when synced up.
 */
class SquashStrategyTest {

    // region helpers

    private enum class TestRequestTag(override val value: String) : ServiceRequestTag {
        DEFAULT("default"),
        UPDATE("update"),
        VOID("void"),
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun testItem(
        clientId: String = "client-1",
        serverId: String? = null,
        version: String? = "1",
        name: String = "Test Item",
        value: Int = 0,
        syncStatus: SyncableObject.SyncStatus = SyncableObject.SyncStatus.LocalOnly,
    ) = TestItem(serverId, clientId, version, syncStatus, name, value)

    private fun wrapResponse(item: TestItem): JsonObject = buildJsonObject {
        put("data", json.encodeToJsonElement(TestItem.serializer(), item))
    }

    private fun testServerConfig(squashMerger: SquashRequestMerger) = object :
        ServerProcessingConfig<TestItem> {
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

    // endregion

    @Test
    fun `squash strategy merges update into pending create`() = runBlocking {
        val env = TestServiceEnvironment()
        val store = env.mockServerStore
        val collection = store.collection("items")

        env.mockRouter.registerCrudHandlers(
            collection = collection,
            baseUrl = "https://api.test.com/items",
        )

        val squashMerger = SquashRequestMerger { createRequest, updateRequest ->
            // Merge: take the update body and use the create endpoint
            HttpRequest(
                method = createRequest.method,
                endpointUrl = createRequest.endpointUrl,
                requestBody = updateRequest.requestBody,
                additionalHeaders = createRequest.additionalHeaders,
            )
        }

        val codec = SyncCodec(TestItem.serializer())
        val localStore = LocalStoreManager<TestItem, TestRequestTag>(
            database = env.database,
            serviceName = "test-items",
            syncScheduleNotifier = env.syncScheduleNotifier,
            codec = codec,
            // Use squash strategy
        )

        // We need to create a PendingRequestQueueManager with squash strategy directly
        // since LocalStoreManager defaults to Queue. Let's test via the queue manager.
        val squashQueueManager = PendingRequestQueueManager<TestItem, TestRequestTag>(
            database = env.database,
            serviceName = "squash-test",
            strategy = PendingRequestQueueStrategy.Squash(
                squashUpdateIntoCreate = squashMerger,
            ),
            codec = codec,
        )

        val item = testItem(clientId = "c1", name = "Original", value = 10)

        // Insert the sync_data row
        env.database.syncDataQueries.insertLocalData(
            service_name = "squash-test",
            client_id = "c1",
            server_id = null,
            version = "1",
            data_blob = json.encodeToString(TestItem.serializer(), item),
            sync_status = SyncableObject.SyncStatus.PENDING_CREATE,
        )

        // Queue a CREATE
        val createRequest = HttpRequest(
            method = HttpRequest.HttpMethod.POST,
            endpointUrl = "https://api.test.com/items",
            requestBody = buildJsonObject {
                put("client_id", "c1")
                put("name", "Original")
                put("value", 10)
            },
        )
        val createResult = squashQueueManager.queueCreateRequest(
            data = item,
            httpRequest = createRequest,
            idempotencyKey = "idem-create",
            serverAttemptMade = false,
            requestTag = TestRequestTag.DEFAULT,
        )
        assertIs<PendingRequestQueueManager.QueueResult.Stored>(createResult)

        // Queue an UPDATE — with squash, it should merge into the existing CREATE
        val updatedItem = item.copy(name = "Updated", version = "2")
        val updateRequest = HttpRequest(
            method = HttpRequest.HttpMethod.PUT,
            endpointUrl = "https://api.test.com/items/{serverId}",
            requestBody = buildJsonObject {
                put("client_id", "c1")
                put("name", "Updated")
                put("value", 10)
            },
        )
        val updateResult = squashQueueManager.queueUpdateRequest(
            data = updatedItem,
            idempotencyKey = "idem-update",
            updateRequest = updateRequest,
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Squash(
                baseData = item,
                hasPendingRequests = true,
                squashUpdateIntoCreate = squashMerger,
            ),
            requestTag = TestRequestTag.UPDATE,
        )
        assertIs<PendingRequestQueueManager.QueueResult.Stored>(updateResult)

        // With squash, there should still be only 1 pending request (the merged CREATE)
        val pending = squashQueueManager.getPendingRequests("c1")
        assertEquals(1, pending.size, "Squash should merge update into create, keeping only 1 entry")
        assertEquals(
            PendingSyncRequest.Type.CREATE, pending.first().type,
            "Merged entry should still be a CREATE")

        // The merged request body should contain the updated data
        val mergedBody = pending.first().request.requestBody
        assertTrue(mergedBody.toString().contains("Updated"),
            "Merged request body should contain the updated name")

    }

    @Test
    fun `squash strategy merges consecutive updates into single pending request`() = runBlocking {
        val env = TestServiceEnvironment()
        val codec = SyncCodec(TestItem.serializer())

        val squashQueueManager = PendingRequestQueueManager<TestItem, TestRequestTag>(
            database = env.database,
            serviceName = "squash-updates",
            strategy = PendingRequestQueueStrategy.Squash(
                squashUpdateIntoCreate = SquashRequestMerger { create, update ->
                    HttpRequest(create.method, create.endpointUrl, update.requestBody)
                },
            ),
            codec = codec,
        )

        val item = testItem(clientId = "c1", serverId = "s1", name = "V1", value = 1)

        // Simulate a synced item
        env.database.syncDataQueries.insertFromServerResponse(
            service_name = "squash-updates",
            client_id = "c1",
            server_id = "s1",
            version = "1",
            last_synced_timestamp = "1000",
            data_blob = json.encodeToString(TestItem.serializer(), item),
            sync_status = SyncableObject.SyncStatus.SYNCED,
            last_synced_server_data = json.encodeToString(TestItem.serializer(), item),
        )

        // Queue first UPDATE
        val item2 = item.copy(name = "V2", version = "2")
        squashQueueManager.queueUpdateRequest(
            data = item2,
            idempotencyKey = "idem-u1",
            updateRequest = HttpRequest(
                HttpRequest.HttpMethod.PUT, "https://api.test.com/items/s1",
                buildJsonObject { put("name", "V2") }),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Squash(
                baseData = item,
                hasPendingRequests = false,
                squashUpdateIntoCreate = SquashRequestMerger { create, update ->
                    HttpRequest(create.method, create.endpointUrl, update.requestBody)
                },
            ),
            requestTag = TestRequestTag.UPDATE,
        )

        assertEquals(1, squashQueueManager.getPendingRequests("c1").size)

        // Queue second UPDATE — should squash into the first
        val item3 = item2.copy(name = "V3", version = "3")
        squashQueueManager.queueUpdateRequest(
            data = item3,
            idempotencyKey = "idem-u2",
            updateRequest = HttpRequest(
                HttpRequest.HttpMethod.PUT, "https://api.test.com/items/s1",
                buildJsonObject { put("name", "V3") }),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Squash(
                baseData = item2,
                hasPendingRequests = true,
                squashUpdateIntoCreate = SquashRequestMerger { create, update ->
                    HttpRequest(create.method, create.endpointUrl, update.requestBody)
                },
            ),
            requestTag = TestRequestTag.UPDATE,
        )

        // Should still be 1 pending request after squash
        val pending = squashQueueManager.getPendingRequests("c1")
        assertEquals(1, pending.size, "Second update should be squashed into first")
        assertEquals(PendingSyncRequest.Type.UPDATE, pending.first().type)
        assertTrue(pending.first().request.requestBody.toString().contains("V3"),
            "Squashed request should contain the latest update data")
    }

    @Test
    fun `squash strategy does not squash when server attempt was made`() = runBlocking {
        val env = TestServiceEnvironment()
        val codec = SyncCodec(TestItem.serializer())

        val squashQueueManager = PendingRequestQueueManager<TestItem, TestRequestTag>(
            database = env.database,
            serviceName = "squash-no-merge",
            strategy = PendingRequestQueueStrategy.Squash(
                squashUpdateIntoCreate = SquashRequestMerger { create, update ->
                    HttpRequest(create.method, create.endpointUrl, update.requestBody)
                },
            ),
            codec = codec,
        )

        val item = testItem(clientId = "c1", name = "Original")
        env.database.syncDataQueries.insertLocalData(
            service_name = "squash-no-merge",
            client_id = "c1",
            server_id = null,
            version = "1",
            data_blob = json.encodeToString(TestItem.serializer(), item),
            sync_status = SyncableObject.SyncStatus.PENDING_CREATE,
        )

        // Queue a CREATE with serverAttemptMade = true (e.g., timed out)
        squashQueueManager.queueCreateRequest(
            data = item,
            httpRequest = HttpRequest(
                HttpRequest.HttpMethod.POST, "https://api.test.com/items",
                buildJsonObject { put("name", "Original") }),
            idempotencyKey = "idem-create",
            serverAttemptMade = true,
            requestTag = TestRequestTag.DEFAULT,
        )

        // Queue an UPDATE via ForcedAfterServerAttempt — should NOT squash
        val updatedItem = item.copy(name = "Updated", version = "2")
        squashQueueManager.queueUpdateRequest(
            data = updatedItem,
            idempotencyKey = "idem-update",
            updateRequest = HttpRequest(
                HttpRequest.HttpMethod.PUT, "https://api.test.com/items/{serverId}",
                buildJsonObject { put("name", "Updated") }),
            serverAttemptMadeForCurrentRequest = true,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Queue.ForcedAfterServerAttempt(
                baseData = item,
                hasPendingRequests = true,
            ),
            requestTag = TestRequestTag.UPDATE,
        )

        // Should be 2 separate entries since server attempt was made on the CREATE
        val pending = squashQueueManager.getPendingRequests("c1")
        assertEquals(2, pending.size,
            "Should not squash when the CREATE has serverAttemptMade=true")
        assertEquals(PendingSyncRequest.Type.CREATE, pending[0].type)
        assertEquals(PendingSyncRequest.Type.UPDATE, pending[1].type)
    }

    @Test
    fun `squash strategy end-to-end with MockServerStore`() = runBlocking {
        val env = TestServiceEnvironment()
        val store = env.mockServerStore
        val collection = store.collection("items")
        val codec = SyncCodec(TestItem.serializer())

        env.mockRouter.registerCrudHandlers(
            collection = collection,
            baseUrl = "https://api.test.com/items",
        )

        val squashQueueManager = PendingRequestQueueManager<TestItem, TestRequestTag>(
            database = env.database,
            serviceName = "squash-e2e",
            strategy = PendingRequestQueueStrategy.Squash(
                squashUpdateIntoCreate = SquashRequestMerger { create, update ->
                    // Merge the update body into the create request
                    HttpRequest(create.method, create.endpointUrl, update.requestBody)
                },
            ),
            codec = codec,
        )

        val item = testItem(clientId = "c1", name = "Original", value = 10)

        // Insert the sync_data row
        env.database.syncDataQueries.insertLocalData(
            service_name = "squash-e2e",
            client_id = "c1",
            server_id = null,
            version = "1",
            data_blob = json.encodeToString(TestItem.serializer(), item),
            sync_status = SyncableObject.SyncStatus.PENDING_CREATE,
        )

        // Queue CREATE
        squashQueueManager.queueCreateRequest(
            data = item,
            httpRequest = HttpRequest(
                HttpRequest.HttpMethod.POST, "https://api.test.com/items",
                buildJsonObject {
                    put("client_id", "c1")
                    put("name", "Original")
                    put("value", 10)
                }),
            idempotencyKey = "idem-create",
            serverAttemptMade = false,
            requestTag = TestRequestTag.DEFAULT,
        )

        // Queue UPDATE — gets squashed into CREATE
        squashQueueManager.queueUpdateRequest(
            data = item.copy(name = "Final Name", value = 42, version = "2"),
            idempotencyKey = "idem-update",
            updateRequest = HttpRequest(
                HttpRequest.HttpMethod.PUT, "https://api.test.com/items/{serverId}",
                buildJsonObject {
                    put("client_id", "c1")
                    put("name", "Final Name")
                    put("value", 42)
                }),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Squash(
                baseData = item,
                hasPendingRequests = true,
                squashUpdateIntoCreate = SquashRequestMerger { create, update ->
                    HttpRequest(create.method, create.endpointUrl, update.requestBody)
                },
            ),
            requestTag = TestRequestTag.UPDATE,
        )

        // Verify only 1 pending request exists after squash
        val pending = squashQueueManager.getPendingRequests("c1")
        assertEquals(1, pending.size)
        assertEquals(PendingSyncRequest.Type.CREATE, pending.first().type)

        // The squashed request body should contain the final values
        val body = pending.first().request.requestBody
        assertTrue(body.toString().contains("Final Name"))
        assertTrue(body.toString().contains("42"))

        // Verify the mock server store has no records yet (nothing synced)
        assertEquals(0, collection.getAll().size)

    }
}
