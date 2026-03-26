package com.les.databuoy

import com.les.databuoy.db.SyncDatabase
import com.les.databuoy.managers.LocalStoreManager
import com.les.databuoy.managers.ServerManager
import com.les.databuoy.serviceconfigs.ConnectivityChecker
import com.les.databuoy.serviceconfigs.ServerProcessingConfig
import com.les.databuoy.serviceconfigs.SyncFetchConfig
import com.les.databuoy.serviceconfigs.SyncUpConfig
import com.les.databuoy.serviceconfigs.SyncUpResult
import com.les.databuoy.sync.SyncDriver
import com.les.databuoy.sync.SyncScheduleNotifier
import com.les.databuoy.sync.SyncUpCoordinator
import com.les.databuoy.syncableobjectservicedatatypes.HttpRequest
import com.les.databuoy.testing.NoOpSyncScheduleNotifier
import com.les.databuoy.testing.TestDatabaseFactory
import com.les.databuoy.utils.SyncCodec
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression tests for two sync-up bugs:
 *
 * **Bug A** — `upsertEntry` SQL was missing `sync_status` in its
 * `ON CONFLICT DO UPDATE` clause, so after a successful CREATE upload
 * with no remaining pending requests the row's status stayed
 * `PENDING_CREATE` instead of becoming `SYNCED`.
 *
 * **Bug B** — `syncUpLocalChanges` fetched every pending request into
 * memory once and iterated over stale snapshots. When processing a
 * CREATE rebased subsequent UPDATE entries in the DB, the loop still
 * used the pre-rebase copies — causing updates to be sent with stale
 * data (missing `server_id`) or skipped entirely.
 */
class SyncUpRegressionTest {

    // region helpers

    private enum class TestRequestTag(override val value: String) : ServiceRequestTag {
        DEFAULT("default"),
    }

    private val noOpNotifier: SyncScheduleNotifier = NoOpSyncScheduleNotifier

    private fun testItem(
        clientId: String,
        serverId: String? = null,
        version: Int = 1,
        name: String = "Test",
        value: Int = 0,
        syncStatus: SyncableObject.SyncStatus = SyncableObject.SyncStatus.LocalOnly,
    ) = TestItem(serverId, clientId, version, syncStatus, name, value)

    private fun makeRequest(
        method: HttpRequest.HttpMethod = HttpRequest.HttpMethod.POST,
        endpoint: String = "https://api.test.com/items",
        body: JsonObject = JsonObject(emptyMap()),
    ) = HttpRequest(
        method = method,
        endpointUrl = endpoint,
        requestBody = body,
    )

    /**
     * Wraps item JSON inside `{"data": <item>}` to match the response shape
     * parsed by [testServerConfig]'s `fromResponseBody`.
     */
    private val json = Json { ignoreUnknownKeys = true }

    private fun wrapResponse(item: TestItem): String = buildJsonObject {
        put("data", json.encodeToJsonElement(TestItem.serializer(), item))
    }.toString()

    /**
     * Creates a [com.les.databuoy.serviceconfigs.ServerProcessingConfig] suitable for tests. The
     * [syncFetchConfig] uses an extremely long cadence so the periodic
     * sync-down never fires during the test.
     */
    private fun testServerConfig() = object : ServerProcessingConfig<TestItem> {
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

    /**
     * Runs a single-service [com.les.databuoy.sync.SyncUpCoordinator] sync pass — the same path production code takes.
     */
    private suspend fun syncUpViaCoordinator(
        driver: SyncDriver<TestItem, TestRequestTag>,
        database: SyncDatabase,
    ): Int {
        return SyncUpCoordinator(listOf(driver), database).syncUpAll()
    }

    // endregion

    // -----------------------------------------------------------------------
    // Bug A — upsertEntry must update sync_status on conflict
    // -----------------------------------------------------------------------

    /**
     * Verifies that when `upsertEntry` is called for a row that already
     * exists in `sync_data`, the `sync_status` column is updated along
     * with every other column.
     *
     * Before the fix, the `ON CONFLICT DO UPDATE` clause omitted
     * `sync_status`, so the value passed in the INSERT was silently
     * dropped and the existing `PENDING_CREATE` value persisted.
     */
    @Test
    fun `upsertEntry updates sync_status on existing row`() {
        val db = TestDatabaseFactory.createInMemory()

        // Insert a locally-created row — status is PENDING_CREATE.
        db.syncDataQueries.insertLocalData(
            service_name = "test",
            client_id = "c1",
            server_id = null,
            version = 1,
            data_blob = """{"client_id":"c1","version":1,"name":"Item","value":0}""",
            sync_status = SyncableObject.SyncStatus.PENDING_CREATE,
        )

        // Simulate the sync-up success path: upsertEntry is called with
        // the server-returned data and sync_status = SYNCED.
        db.syncDataQueries.upsertEntry(
            service_name = "test",
            client_id = "c1",
            server_id = "server_1",
            version = 1,
            last_synced_timestamp = "1000",
            data_blob = """{"client_id":"c1","server_id":"server_1","version":1,"name":"Item","value":0}""",
            sync_status = SyncableObject.SyncStatus.SYNCED,
            last_synced_server_data = """{"client_id":"c1","server_id":"server_1","version":1,"name":"Item","value":0}""",
        )

        // Verify sync_status was updated.
        val row = db.syncDataQueries.getData(
            service_name = "test",
            client_id = "c1",
            server_id = "server_1",
        ).executeAsOne()

        assertEquals(SyncableObject.SyncStatus.SYNCED, row.sync_status,
            "sync_status should be SYNCED after upsertEntry, not left at the old value")
        // getData matched on server_id = "server_1", proving it was set.
        assertEquals("1000", row.last_synced_timestamp)
    }

    /**
     * End-to-end variant of Bug A: a CREATE-only item (no pending
     * UPDATEs) goes through the full `syncUpLocalChanges` flow. After
     * the CREATE succeeds, the `NoPendingRequestRemaining` path calls
     * `upsertEntry` — which must transition `sync_status` to `SYNCED`.
     */
    @Test
    fun `syncUp for create-only item transitions status to SYNCED`() = runBlocking {
        val db = TestDatabaseFactory.createInMemory()
        val item = testItem(clientId = "c2", name = "CreateOnly", value = 42)
        val serverItem = item.copy(serverId = "server_2")

        // Mock server returns the created item with a server_id.
        val mockEngine = MockEngine {
            respond(
                content = wrapResponse(serverItem),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val serverManager = ServerManager(
            serviceBaseHeaders = emptyList(),
            httpClient = HttpClient(mockEngine),
        )

        val localStore = LocalStoreManager<TestItem, TestRequestTag>(
            database = db,
            serviceName = "test",
            syncScheduleNotifier = noOpNotifier,
            codec = SyncCodec(TestItem.serializer()),
        )

        // Simulate offline create: insert into sync_data + queue a pending CREATE.
        localStore.insertLocalData(
            data = item,
            httpRequest = makeRequest(),
            idempotencyKey = "idem-create-c2",
            requestTag = TestRequestTag.DEFAULT,
        )

        val driver = SyncDriver(
            serverManager = serverManager,
            connectivityChecker = object : ConnectivityChecker {
                override fun isOnline() = false
            },
            codec = SyncCodec(TestItem.serializer()),
            serverProcessingConfig = testServerConfig(),
            localStoreManager = localStore,
            serviceName = "test",
            autoStart = false,
        )

        // Act
        val synced = syncUpViaCoordinator(driver, db)

        // Assert
        assertEquals(1, synced, "The single CREATE should have synced")
        // getData matches on server_id = "server_2", proving it was set.
        val row = db.syncDataQueries.getData("test", "c2", "server_2").executeAsOne()
        assertEquals(SyncableObject.SyncStatus.SYNCED, row.sync_status,
            "sync_status must be SYNCED after the CREATE upload completes")

        driver.close()
    }

    // -----------------------------------------------------------------------
    // Bug B — stale snapshot: UPDATEs must use rebased data after CREATE
    // -----------------------------------------------------------------------

    /**
     * Reproduces the offline queue scenario:
     *
     *   1. CREATE item 1
     *   2. UPDATE item 1
     *   3. Sync up
     *
     * After the CREATE succeeds the pending UPDATE is rebased in the DB
     * with the server-assigned `server_id`. The sync loop must re-fetch
     * the entry so the UPDATE request can resolve the `{serverId}`
     * placeholder and actually be sent.
     *
     * Before the fix, the loop used a stale in-memory snapshot (with
     * `serverId = null`), causing the UPDATE to be skipped.
     */
    @Test
    fun `syncUp re-fetches pending entries so rebased UPDATE gets server_id`() = runBlocking {
        val db = TestDatabaseFactory.createInMemory()

        // The original data stored in the CREATE.
        val createData = testItem(clientId = "c1", name = "Original", value = 10, version = 1)
        // The data after the local update (offline).
        val updateData = createData.copy(name = "Updated", version = 2)
        // What the server returns for the CREATE — includes a server_id.
        val createResponse = createData.copy(serverId = "server_1")
        // What the server returns for the UPDATE.
        val updateResponse = updateData.copy(serverId = "server_1")

        var requestCount = 0
        val mockEngine = MockEngine { request ->
            requestCount++
            val responseItem = if (requestCount == 1) createResponse else updateResponse
            respond(
                content = wrapResponse(responseItem),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val serverManager = ServerManager(
            serviceBaseHeaders = emptyList(),
            httpClient = HttpClient(mockEngine),
        )

        val localStore = LocalStoreManager<TestItem, TestRequestTag>(
            database = db,
            serviceName = "test",
            syncScheduleNotifier = noOpNotifier,
            codec = SyncCodec(TestItem.serializer()),
        )

        // Step 1: Offline CREATE item 1.
        localStore.insertLocalData(
            data = createData,
            httpRequest = makeRequest(),
            idempotencyKey = "idem-create-c1",
            requestTag = TestRequestTag.DEFAULT,
        )

        // Step 2: Offline UPDATE item 1.
        // The endpoint uses {serverId} which must be resolved from rebased data.
        val updateEndpoint = "https://api.test.com/items/${HttpRequest.SERVER_ID_PLACEHOLDER}"
        localStore.updateLocalData(
            data = updateData,
            idempotencyKey = "idem-update-c1",
            updateRequest = makeRequest(
                method = HttpRequest.HttpMethod.PUT,
                endpoint = updateEndpoint,
                body = buildJsonObject { put("name", "Updated") },
            ),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Queue.Preferred(
                baseData = createData, // base for the 3-way merge
                hasPendingRequests = true,
            ),
            requestTag = TestRequestTag.DEFAULT,
        )

        val driver = SyncDriver(
            serverManager = serverManager,
            connectivityChecker = object : ConnectivityChecker {
                override fun isOnline() = false
            },
            codec = SyncCodec(TestItem.serializer()),
            serverProcessingConfig = testServerConfig(),
            localStoreManager = localStore,
            serviceName = "test",
            autoStart = false,
        )

        // Act — should process CREATE *and* UPDATE in one pass.
        val synced = syncUpViaCoordinator(driver, db)

        // Assert
        assertEquals(2, synced,
            "Both the CREATE and the UPDATE should have been processed in a single sync pass")
        assertEquals(2, requestCount,
            "Two HTTP requests should have been made (CREATE + UPDATE)")

        // getData matches on server_id = "server_1", proving it was set.
        val row = db.syncDataQueries.getData("test", "c1", "server_1").executeAsOne()
        assertEquals(SyncableObject.SyncStatus.SYNCED, row.sync_status,
            "sync_status should be SYNCED after both requests complete")

        // The pending queue should be empty.
        val remaining = localStore.pendingRequestQueueManager.getPendingRequests("c1")
        assertEquals(0, remaining.size,
            "No pending requests should remain after both entries synced")

        driver.close()
    }

    /**
     * Full scenario from the bug report with two items:
     *
     *   1. CREATE item 1
     *   2. CREATE item 2
     *   3. UPDATE item 1 (update 1)
     *   4. UPDATE item 1 (update 2)
     *   5. Sync up
     *
     * After sync, both items should be fully synced with their server_ids
     * and no pending requests remaining.
     */
    @Test
    fun `full offline queue scenario syncs all items correctly`() = runBlocking {
        val db = TestDatabaseFactory.createInMemory()

        val item1Create = testItem(clientId = "c1", name = "Item 1", value = 10, version = 1)
        val item2Create = testItem(clientId = "c2", name = "Item 2", value = 20, version = 1)
        val item1Update1 = item1Create.copy(name = "Item 1 - Edit 1", version = 2)
        val item1Update2 = item1Create.copy(name = "Item 1 - Edit 2", version = 3)

        val item1ServerCreate = item1Create.copy(serverId = "s1")
        val item2ServerCreate = item2Create.copy(serverId = "s2")
        val item1ServerUpdate1 = item1Update1.copy(serverId = "s1")
        val item1ServerUpdate2 = item1Update2.copy(serverId = "s1")

        // Queue server responses in order of expected requests.
        val responseQueue = ArrayDeque(listOf(
            wrapResponse(item1ServerCreate),
            wrapResponse(item2ServerCreate),
            wrapResponse(item1ServerUpdate1),
            wrapResponse(item1ServerUpdate2),
        ))

        val mockEngine = MockEngine {
            respond(
                content = responseQueue.removeFirst(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val serverManager = ServerManager(
            serviceBaseHeaders = emptyList(),
            httpClient = HttpClient(mockEngine),
        )

        val localStore = LocalStoreManager<TestItem, TestRequestTag>(
            database = db,
            serviceName = "test",
            syncScheduleNotifier = noOpNotifier,
            codec = SyncCodec(TestItem.serializer()),
        )

        val updateEndpoint = "https://api.test.com/items/${HttpRequest.SERVER_ID_PLACEHOLDER}"

        // 1. CREATE item 1
        localStore.insertLocalData(
            data = item1Create,
            httpRequest = makeRequest(),
            idempotencyKey = "idem-create-c1",
            requestTag = TestRequestTag.DEFAULT,
        )
        // 2. CREATE item 2
        localStore.insertLocalData(
            data = item2Create,
            httpRequest = makeRequest(),
            idempotencyKey = "idem-create-c2",
            requestTag = TestRequestTag.DEFAULT,
        )
        // 3. UPDATE item 1 (update 1)
        localStore.updateLocalData(
            data = item1Update1,
            idempotencyKey = "idem-update1-c1",
            updateRequest = makeRequest(
                method = HttpRequest.HttpMethod.PUT,
                endpoint = updateEndpoint,
            ),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Queue.Preferred(
                baseData = item1Create,
                hasPendingRequests = true,
            ),
            requestTag = TestRequestTag.DEFAULT,
        )
        // 4. UPDATE item 1 (update 2)
        localStore.updateLocalData(
            data = item1Update2,
            idempotencyKey = "idem-update2-c1",
            updateRequest = makeRequest(
                method = HttpRequest.HttpMethod.PUT,
                endpoint = updateEndpoint,
            ),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Queue.Preferred(
                baseData = item1Update1,
                hasPendingRequests = true,
            ),
            requestTag = TestRequestTag.DEFAULT,
        )

        val driver = SyncDriver(
            serverManager = serverManager,
            connectivityChecker = object : ConnectivityChecker {
                override fun isOnline() = false
            },
            codec = SyncCodec(TestItem.serializer()),
            serverProcessingConfig = testServerConfig(),
            localStoreManager = localStore,
            serviceName = "test",
            autoStart = false,
        )

        // Act
        val synced = syncUpViaCoordinator(driver, db)

        // Assert — all 4 operations should succeed in one pass.
        assertEquals(4, synced, "All 4 pending requests should have been synced")

        // Item 1: SYNCED with server_id, no pending requests.
        // getData matches on server_id = "s1", proving it was set.
        val row1 = db.syncDataQueries.getData("test", "c1", "s1").executeAsOne()
        assertEquals(SyncableObject.SyncStatus.SYNCED, row1.sync_status,
            "Item 1 sync_status should be SYNCED")
        assertEquals(0, localStore.pendingRequestQueueManager.getPendingRequests("c1").size,
            "Item 1 should have no remaining pending requests")

        // Item 2: SYNCED with server_id, no pending requests.
        // getData matches on server_id = "s2", proving it was set.
        val row2 = db.syncDataQueries.getData("test", "c2", "s2").executeAsOne()
        assertEquals(SyncableObject.SyncStatus.SYNCED, row2.sync_status,
            "Item 2 sync_status should be SYNCED")
        assertEquals(0, localStore.pendingRequestQueueManager.getPendingRequests("c2").size,
            "Item 2 should have no remaining pending requests")

        driver.close()
    }
}
