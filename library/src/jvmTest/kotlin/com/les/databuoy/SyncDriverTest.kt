package com.les.databuoy

import com.les.databuoy.db.SyncDatabase
import com.les.databuoy.testing.NoOpSyncLogger
import com.les.databuoy.testing.NoOpSyncScheduleNotifier
import com.les.databuoy.testing.TestDatabaseFactory
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SyncDriverTest {

    // region helpers

    private enum class TestRequestTag(override val value: String) : ServiceRequestTag {
        DEFAULT("default"),
    }

    private val logger: SyncLogger = NoOpSyncLogger
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
    ) = HttpRequest(method = method, endpointUrl = endpoint, requestBody = body)

    private val json = Json { ignoreUnknownKeys = true }

    private fun wrapResponse(item: TestItem): String = buildJsonObject {
        put("data", json.encodeToJsonElement(TestItem.serializer(), item))
    }.toString()

    private fun wrapListResponse(items: List<TestItem>): String = buildJsonObject {
        put("data", Json.encodeToJsonElement(
            kotlinx.serialization.builtins.ListSerializer(TestItem.serializer()), items
        ))
    }.toString()

    private fun testServerConfig() = object : ServerProcessingConfig<TestItem> {
        override val syncFetchConfig = SyncFetchConfig.GetFetchConfig<TestItem>(
            endpoint = "https://api.test.com/items",
            syncCadenceSeconds = 999_999,
            transformResponse = { response ->
                val items = response["data"]?.jsonArray ?: return@GetFetchConfig emptyList()
                items.map {
                    Json.decodeFromJsonElement(TestItem.serializer(), it.jsonObject)
                        .withSyncStatus(SyncableObject.SyncStatus.Synced(""))
                }
            },
        )
        override val syncUpConfig = object : SyncUpConfig<TestItem>() {
            override fun fromResponseBody(requestTag: String, responseBody: JsonObject): SyncUpResult<TestItem> {
                val data = responseBody["data"]?.jsonObject ?: return SyncUpResult.Failed.RemovePendingRequest
                return SyncUpResult.Success(
                    Json.decodeFromJsonElement(TestItem.serializer(), data)
                        .withSyncStatus(SyncableObject.SyncStatus.Synced(""))
                )
            }
        }
        override val globalHeaders: List<Pair<String, String>> = emptyList()
    }

    private fun createDriver(
        database: SyncDatabase,
        mockEngine: MockEngine,
        online: Boolean = true,
        connectivityChecker: ConnectivityChecker? = null,
    ): Pair<SyncDriver<TestItem, TestRequestTag>, LocalStoreManager<TestItem, TestRequestTag>> {
        val localStore = LocalStoreManager<TestItem, TestRequestTag>(
            database = database, serviceName = "test",
            syncScheduleNotifier = noOpNotifier,
            codec = SyncCodec(TestItem.serializer()), logger = logger,
        )
        val serverManager = ServerManager(
            serviceBaseHeaders = emptyList(), logger = logger,
            httpClient = HttpClient(mockEngine),
        )
        val checker = connectivityChecker ?: object : ConnectivityChecker {
            override fun isOnline(): Boolean = online
        }
        val driver = object : SyncDriver<TestItem, TestRequestTag>(
            serverManager, checker, SyncCodec(TestItem.serializer()),
            testServerConfig(), localStore, logger, noOpNotifier,
        ) {}
        driver.stopPeriodicSyncDown()
        return driver to localStore
    }

    // endregion

    // region syncDownFromServer

    @Test
    fun `syncDownFromServer - clean upsert inserts server items as SYNCED`() = runBlocking {
        val db = TestDatabaseFactory.createInMemory()
        val status = DataBuoyStatus(db)
        val serverItems = listOf(
            testItem(clientId = "c1", serverId = "s1", name = "Item 1", value = 10),
            testItem(clientId = "c2", serverId = "s2", name = "Item 2", value = 20),
        )
        val mockEngine = MockEngine {
            respond(
                content = wrapListResponse(serverItems),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val (driver, localStore) = createDriver(db, mockEngine = mockEngine)

        driver.syncDownFromServer()

        val item1 = localStore.getData(clientId = "c1", serverId = "s1")
        assertNotNull(item1)
        assertTrue(item1.syncStatus is SyncableObject.SyncStatus.Synced)
        assertEquals("Item 1", item1.data.name)

        val item2 = localStore.getData(clientId = "c2", serverId = "s2")
        assertNotNull(item2)
        assertTrue(item2.syncStatus is SyncableObject.SyncStatus.Synced)

        assertEquals(0, status.pendingRequestCount.value)
        assertFalse(status.hasPendingConflicts.value)

        driver.close()
    }

    @Test
    fun `syncDownFromServer refreshes conflict status after a conflicting merge`() = runBlocking {
        val db = TestDatabaseFactory.createInMemory()
        val status = DataBuoyStatus(db)
        val serverItem = testItem(clientId = "c1", serverId = "s1", name = "Server", value = 1)
        val mockEngine = MockEngine {
            respond(
                content = wrapListResponse(listOf(serverItem)),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val (driver, localStore) = createDriver(db, mockEngine = mockEngine)

        localStore.insertLocalData(
            data = testItem(clientId = "c1", name = "Local", value = 1),
            httpRequest = makeRequest(),
            idempotencyKey = "idem-c1",
            requestTag = TestRequestTag.DEFAULT,
        )

        driver.syncDownFromServer()

        assertEquals(1, status.pendingRequestCount.value)
        assertTrue(status.hasPendingConflicts.value)

        driver.close()
    }

    @Test
    fun `syncDownFromServer - skips when offline`() = runBlocking {
        val db = TestDatabaseFactory.createInMemory()
        var requestCount = 0
        val mockEngine = MockEngine {
            requestCount++
            respond(content = wrapListResponse(emptyList()), status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val (driver, _) = createDriver(db, mockEngine = mockEngine, online = false)

        driver.syncDownFromServer()

        assertEquals(0, requestCount)
        driver.close()
    }

    @Test
    fun `syncDownFromServer - handles connection error gracefully`() = runBlocking {
        val db = TestDatabaseFactory.createInMemory()
        val mockEngine = MockEngine { throw java.io.IOException("Connection refused") }
        val (driver, _) = createDriver(db, mockEngine = mockEngine)

        // Should not throw
        driver.syncDownFromServer()
        driver.close()
    }

    @Test
    fun `syncDownFromServer - merges with pending local changes`() = runBlocking {
        val db = TestDatabaseFactory.createInMemory()
        val localItem = testItem(clientId = "c1", serverId = "s1", name = "Original", value = 10, version = 1)
        val serverItem = localItem.copy(name = "ServerEdit", value = 10, version = 2)

        val createResponse = wrapResponse(localItem.copy(serverId = "s1"))
        val syncDownResponse = wrapListResponse(listOf(serverItem))

        var requestIndex = 0
        val responses = listOf(createResponse, syncDownResponse)
        val mockEngine = MockEngine {
            respond(content = responses[requestIndex++], status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val (driver, localStore) = createDriver(db, mockEngine = mockEngine)

        // Insert and sync-up the CREATE
        localStore.insertLocalData(
            data = localItem.withSyncStatus(SyncableObject.SyncStatus.LocalOnly),
            httpRequest = makeRequest(), idempotencyKey = "idem-create",
            requestTag = TestRequestTag.DEFAULT,
        )
        val coordinator = SyncUpCoordinator(
            participants = listOf(object : SyncUpParticipant {
                override val serviceName = "test"
                override suspend fun syncUpSinglePendingRequest(pendingRequestId: Int) =
                    driver.syncUpSinglePendingRequest(pendingRequestId)
            }),
            database = db, logger = logger,
        )
        coordinator.syncUpAll()

        // Queue a pending UPDATE
        localStore.updateLocalData(
            data = localItem.copy(name = "LocalEdit", version = 2),
            idempotencyKey = "idem-update", lastSyncedData = localItem,
            instruction = PendingRequestQueueManager.UpdateQueueInstruction.Store(
                httpRequest = makeRequest(method = HttpRequest.HttpMethod.PUT,
                    endpoint = "https://api.test.com/items/${HttpRequest.SERVER_ID_PLACEHOLDER}"),
                buildRequest = { _, updated, _, _, _ ->
                    makeRequest(method = HttpRequest.HttpMethod.PUT,
                        endpoint = "https://api.test.com/items/${HttpRequest.SERVER_ID_PLACEHOLDER}")
                },
            ),
            requestTag = TestRequestTag.DEFAULT,
        )

        // Sync down server edit — should merge with pending local UPDATE
        driver.syncDownFromServer()

        val result = localStore.getData(clientId = "c1", serverId = "s1")
        assertNotNull(result)
        val pending = localStore.pendingRequestQueueManager.getPendingRequests("c1")
        assertTrue(pending.isNotEmpty(), "Pending UPDATE should remain after sync-down merge")

        driver.close()
    }

    // endregion

    // region syncUpSinglePendingRequest

    @Test
    fun `syncUpSinglePendingRequest - resolves serverId placeholder`() = runBlocking {
        val db = TestDatabaseFactory.createInMemory()
        val createItem = testItem(clientId = "c1", name = "Item", value = 10, version = 1)
        val createResponse = createItem.copy(serverId = "s1")
        val updateResponse = createItem.copy(serverId = "s1", name = "Updated", version = 2)

        var requestCount = 0
        val mockEngine = MockEngine {
            requestCount++
            val item = if (requestCount == 1) createResponse else updateResponse
            respond(content = wrapResponse(item), status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val (driver, localStore) = createDriver(db, mockEngine = mockEngine, online = false)

        localStore.insertLocalData(
            data = createItem, httpRequest = makeRequest(),
            idempotencyKey = "idem-create", requestTag = TestRequestTag.DEFAULT,
        )
        localStore.updateLocalData(
            data = createItem.copy(name = "Updated", version = 2),
            idempotencyKey = "idem-update", lastSyncedData = createItem,
            instruction = PendingRequestQueueManager.UpdateQueueInstruction.Store(
                httpRequest = makeRequest(method = HttpRequest.HttpMethod.PUT,
                    endpoint = "https://api.test.com/items/${HttpRequest.SERVER_ID_PLACEHOLDER}"),
                buildRequest = { _, updated, _, _, _ ->
                    makeRequest(method = HttpRequest.HttpMethod.PUT,
                        endpoint = "https://api.test.com/items/${HttpRequest.SERVER_ID_PLACEHOLDER}")
                },
            ),
            requestTag = TestRequestTag.DEFAULT,
        )

        val coordinator = SyncUpCoordinator(
            participants = listOf(object : SyncUpParticipant {
                override val serviceName = "test"
                override suspend fun syncUpSinglePendingRequest(pendingRequestId: Int) =
                    driver.syncUpSinglePendingRequest(pendingRequestId)
            }),
            database = db, logger = logger,
        )
        val synced = coordinator.syncUpAll()

        assertEquals(2, synced)
        assertEquals(2, requestCount)
        val row = db.syncDataQueries.getData("test", "c1", "s1").executeAsOne()
        assertEquals(SyncableObject.SyncStatus.SYNCED, row.sync_status)

        driver.close()
    }

    @Test
    fun `syncUpSinglePendingRequest - skips when serverId unresolved`() = runBlocking {
        val db = TestDatabaseFactory.createInMemory()
        var requestCount = 0
        val mockEngine = MockEngine {
            requestCount++
            respond(content = "{}", status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val localStore = LocalStoreManager<TestItem, TestRequestTag>(
            database = db, serviceName = "test", syncScheduleNotifier = noOpNotifier,
            codec = SyncCodec(TestItem.serializer()), logger = logger,
        )
        val serverManager = ServerManager(
            serviceBaseHeaders = emptyList(), logger = logger, httpClient = HttpClient(mockEngine),
        )
        val driver = object : SyncDriver<TestItem, TestRequestTag>(
            serverManager, object : ConnectivityChecker { override fun isOnline() = false },
            SyncCodec(TestItem.serializer()), testServerConfig(), localStore, logger, noOpNotifier,
        ) {}
        driver.stopPeriodicSyncDown()

        val item = testItem(clientId = "c1", name = "NoServerId")
        localStore.insertLocalData(
            data = item, httpRequest = makeRequest(),
            idempotencyKey = "idem-create", requestTag = TestRequestTag.DEFAULT,
        )
        localStore.updateLocalData(
            data = item.copy(name = "Edited", version = 2),
            idempotencyKey = "idem-update", lastSyncedData = item,
            instruction = PendingRequestQueueManager.UpdateQueueInstruction.Store(
                httpRequest = makeRequest(method = HttpRequest.HttpMethod.PUT,
                    endpoint = "https://api.test.com/items/${HttpRequest.SERVER_ID_PLACEHOLDER}"),
                buildRequest = { _, updated, _, _, _ ->
                    makeRequest(method = HttpRequest.HttpMethod.PUT,
                        endpoint = "https://api.test.com/items/${HttpRequest.SERVER_ID_PLACEHOLDER}")
                },
            ),
            requestTag = TestRequestTag.DEFAULT,
        )

        val updateEntry = localStore.pendingRequestQueueManager.getPendingRequests("c1")
            .find { it.type == PendingSyncRequest.Type.UPDATE }
        assertNotNull(updateEntry)

        driver.syncUpSinglePendingRequest(updateEntry.pendingRequestId)

        assertEquals(0, requestCount, "No HTTP request when serverId unresolved")
        driver.close()
    }

    // endregion

    // region lifecycle

    @Test
    fun `stopPeriodicSyncDown and close do not crash`() {
        val db = TestDatabaseFactory.createInMemory()
        val mockEngine = MockEngine {
            respond(content = "{}", status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val (driver, _) = createDriver(db, mockEngine = mockEngine, online = false)

        driver.stopPeriodicSyncDown()
        driver.stopPeriodicSyncDown()
        driver.close()
    }

    // endregion
}
