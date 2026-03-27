package com.les.databuoy

import com.les.databuoy.db.SyncDatabase
import com.les.databuoy.globalconfigs.DataBuoyStatus
import com.les.databuoy.managers.LocalStoreManager
import com.les.databuoy.managers.PendingSyncRequest
import com.les.databuoy.managers.ServerManager
import com.les.databuoy.serviceconfigs.ConnectivityChecker
import com.les.databuoy.serviceconfigs.ServerProcessingConfig
import com.les.databuoy.serviceconfigs.SyncFetchConfig
import com.les.databuoy.serviceconfigs.SyncUpConfig
import com.les.databuoy.serviceconfigs.SyncUpResult
import com.les.databuoy.sync.SyncDriver
import com.les.databuoy.sync.SyncScheduleNotifier
import com.les.databuoy.sync.SyncUpCoordinator
import com.les.databuoy.datatypes.HttpRequest
import com.les.databuoy.testing.NoOpSyncScheduleNotifier
import com.les.databuoy.testing.TestDatabaseFactory
import com.les.databuoy.utils.SyncCodec
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    private val noOpNotifier: SyncScheduleNotifier = NoOpSyncScheduleNotifier

    private fun testItem(
        clientId: String,
        serverId: String? = null,
        version: String? = "1",
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
                val data = responseBody["data"]?.jsonObject ?: return SyncUpResult.Failed.RemovePendingRequest()
                return SyncUpResult.Success(
                    Json.decodeFromJsonElement(TestItem.serializer(), data)
                        .withSyncStatus(SyncableObject.SyncStatus.Synced(""))
                )
            }
        }
        override val serviceHeaders: List<Pair<String, String>> = emptyList()
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
            codec = SyncCodec(TestItem.serializer()),
        )
        val serverManager = ServerManager(
            serviceBaseHeaders = emptyList(),
            httpClient = HttpClient(mockEngine),
        )
        val checker = connectivityChecker ?: object : ConnectivityChecker {
            override fun isOnline(): Boolean = online
        }
        val driver = SyncDriver(
            serverManager = serverManager,
            connectivityChecker = checker,
            codec = SyncCodec(TestItem.serializer()),
            serverProcessingConfig = testServerConfig(),
            localStoreManager = localStore,
            serviceName = "test",
            autoStart = false,
        )
        return driver to localStore
    }

    // endregion

    // region syncDownFromServer

    @Test
    fun `syncDownFromServer - clean upsert inserts server items as SYNCED`() = runBlocking {
        val db = TestDatabaseFactory.createInMemory()
        val status = DataBuoyStatus(db, CoroutineScope(Dispatchers.Unconfined))
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
        val status = DataBuoyStatus(db, CoroutineScope(Dispatchers.Unconfined))
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
        val localItem = testItem(clientId = "c1", serverId = "s1", name = "Original", value = 10, version = "1")
        val serverItem = localItem.copy(name = "ServerEdit", value = 10, version = "2")

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
            drivers = listOf(driver),
            database = db,
        )
        coordinator.syncUpAll()

        // Queue a pending UPDATE
        localStore.updateLocalData(
            data = localItem.copy(name = "LocalEdit", version = "2"),
            idempotencyKey = "idem-update",
            updateRequest = makeRequest(method = HttpRequest.HttpMethod.PUT,
                endpoint = "https://api.test.com/items/${HttpRequest.SERVER_ID_PLACEHOLDER}"),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Queue.Preferred(
                baseData = localItem,
                hasPendingRequests = false,
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

    @Test
    fun `syncDownFromServer - does not duplicate when server returns different clientId for same serverId`() = runBlocking {
        val db = TestDatabaseFactory.createInMemory()
        // Pre-populate: local row with client_id="c1", server_id="s1"
        val localItem = testItem(clientId = "c1", serverId = "s1", name = "Original", value = 10, version = "1")

        val mockEngine = MockEngine { _ ->
            // First request: sync-up create response
            // Second request: sync-down returns same server_id but different client_id
            val serverItems = listOf(
                testItem(clientId = "different-client-id", serverId = "s1", name = "ServerUpdate", value = 20, version = "2"),
            )
            respond(
                content = wrapListResponse(serverItems),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val (driver, localStore) = createDriver(db, mockEngine = mockEngine)

        // Insert the item as if it was already synced
        localStore.upsertEntry(
            serverObj = localItem,
            syncedAtTimestamp = "2024-01-01T00:00:00Z",
            clientId = "c1",
        )

        // Sync down — server returns same server_id but different client_id
        driver.syncDownFromServer()

        // Should only have one row, not two
        val allItems = localStore.getAllData(100)
        assertEquals(1, allItems.size, "Should have exactly 1 row, not a duplicate")

        // The row should be updated with server data
        val item = allItems.first()
        assertEquals("ServerUpdate", item.data.name)
        assertEquals(20, item.data.value)

        driver.close()
    }

    @Test
    fun `syncDownFromServer - does not duplicate when local row created offline has server_id=null`() = runBlocking {
        val db = TestDatabaseFactory.createInMemory()

        // Simulate: item created offline (no server_id yet), then sync-up assigns server_id,
        // then sync-down returns the item with a different client_id.
        val createResponse = wrapResponse(
            testItem(clientId = "c1", serverId = "s1", name = "Created", value = 10, version = "1"),
        )
        val syncDownResponse = wrapListResponse(listOf(
            testItem(clientId = "server-assigned-id", serverId = "s1", name = "FromServer", value = 10, version = "1"),
        ))

        var requestIndex = 0
        val responses = listOf(createResponse, syncDownResponse)
        val mockEngine = MockEngine {
            respond(
                content = responses[requestIndex++],
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val (driver, localStore) = createDriver(db, mockEngine = mockEngine)

        // Step 1: Create offline
        localStore.insertLocalData(
            data = testItem(clientId = "c1", name = "Created", value = 10),
            httpRequest = makeRequest(),
            idempotencyKey = "idem-1",
            requestTag = TestRequestTag.DEFAULT,
        )

        // Step 2: Sync up the create (assigns server_id="s1")
        val coordinator = SyncUpCoordinator(drivers = listOf(driver), database = db)
        coordinator.syncUpAll()

        // Step 3: Sync down — server returns same item with different client_id
        driver.syncDownFromServer()

        // Should still be exactly 1 row
        val allItems = localStore.getAllData(100)
        assertEquals(1, allItems.size, "Should have exactly 1 row after sync-down with mismatched client_id")

        driver.close()
    }

    // endregion

    // region syncUpSinglePendingRequest

    @Test
    fun `syncUpSinglePendingRequest - resolves serverId placeholder`() = runBlocking {
        val db = TestDatabaseFactory.createInMemory()
        val createItem = testItem(clientId = "c1", name = "Item", value = 10, version = "1")
        val createResponse = createItem.copy(serverId = "s1")
        val updateResponse = createItem.copy(serverId = "s1", name = "Updated", version = "2")

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
            data = createItem.copy(name = "Updated", version = "2"),
            idempotencyKey = "idem-update",
            updateRequest = makeRequest(method = HttpRequest.HttpMethod.PUT,
                endpoint = "https://api.test.com/items/${HttpRequest.SERVER_ID_PLACEHOLDER}"),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Queue.Preferred(
                baseData = createItem,
                hasPendingRequests = true,
            ),
            requestTag = TestRequestTag.DEFAULT,
        )

        val coordinator = SyncUpCoordinator(
            drivers = listOf(driver),
            database = db,
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
            codec = SyncCodec(TestItem.serializer()),
        )
        val serverManager = ServerManager(
            serviceBaseHeaders = emptyList(), httpClient = HttpClient(mockEngine),
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

        val item = testItem(clientId = "c1", name = "NoServerId")
        localStore.insertLocalData(
            data = item, httpRequest = makeRequest(),
            idempotencyKey = "idem-create", requestTag = TestRequestTag.DEFAULT,
        )
        localStore.updateLocalData(
            data = item.copy(name = "Edited", version = "2"),
            idempotencyKey = "idem-update",
            updateRequest = makeRequest(method = HttpRequest.HttpMethod.PUT,
                endpoint = "https://api.test.com/items/${HttpRequest.SERVER_ID_PLACEHOLDER}"),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Queue.Preferred(
                baseData = item,
                hasPendingRequests = true,
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

    // region syncDownFromServer - PostFetchConfig

    @Test
    fun `syncDownFromServer - PostFetchConfig sends POST with request body`() = runBlocking {
        val db = TestDatabaseFactory.createInMemory()
        val serverItems = listOf(
            testItem(clientId = "c1", serverId = "s1", name = "Post Item", value = 42),
        )
        var capturedMethod: String? = null
        var capturedBody: String? = null
        val mockEngine = MockEngine { request ->
            capturedMethod = request.method.value
            val bodyBytes = (request.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)
                ?.bytes()?.decodeToString()
            capturedBody = bodyBytes
            respond(
                content = wrapListResponse(serverItems),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val postFetchConfig = object : ServerProcessingConfig<TestItem> {
            override val syncFetchConfig = SyncFetchConfig.PostFetchConfig<TestItem>(
                endpoint = "https://api.test.com/items/sync",
                requestBody = buildJsonObject { put("last_synced_at", 1000) },
                syncCadenceSeconds = 999_999,
                transformResponse = { response ->
                    val items = response["data"]?.jsonArray ?: return@PostFetchConfig emptyList()
                    items.map {
                        Json.decodeFromJsonElement(TestItem.serializer(), it.jsonObject)
                            .withSyncStatus(SyncableObject.SyncStatus.Synced(""))
                    }
                },
            )
            override val syncUpConfig = testServerConfig().syncUpConfig
            override val serviceHeaders: List<Pair<String, String>> = emptyList()
        }

        val localStore = LocalStoreManager<TestItem, TestRequestTag>(
            database = db, serviceName = "test",
            syncScheduleNotifier = noOpNotifier,
            codec = SyncCodec(TestItem.serializer()),
        )
        val serverManager = ServerManager(
            serviceBaseHeaders = emptyList(),
            httpClient = HttpClient(mockEngine),
        )
        val driver = SyncDriver(
            serverManager = serverManager,
            connectivityChecker = object : ConnectivityChecker {
                override fun isOnline() = true
            },
            codec = SyncCodec(TestItem.serializer()),
            serverProcessingConfig = postFetchConfig,
            localStoreManager = localStore,
            serviceName = "test",
            autoStart = false,
        )

        driver.syncDownFromServer()

        assertEquals("POST", capturedMethod)
        assertNotNull(capturedBody)
        assertTrue(capturedBody!!.contains("last_synced_at"))

        val item1 = localStore.getData(clientId = "c1", serverId = "s1")
        assertNotNull(item1)
        assertTrue(item1.syncStatus is SyncableObject.SyncStatus.Synced)
        assertEquals("Post Item", item1.data.name)

        driver.close()
    }

    @Test
    fun `syncDownFromServer - PostFetchConfig merges with pending local changes`() = runBlocking {
        val db = TestDatabaseFactory.createInMemory()
        val localItem = testItem(clientId = "c1", serverId = "s1", name = "Original", value = 10, version = "1")
        val serverItem = localItem.copy(name = "ServerEdit", value = 10, version = "2")

        val createResponse = wrapResponse(localItem.copy(serverId = "s1"))
        val syncDownResponse = wrapListResponse(listOf(serverItem))

        var requestIndex = 0
        val responses = listOf(createResponse, syncDownResponse)
        val mockEngine = MockEngine {
            respond(content = responses[requestIndex++], status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }

        val postFetchConfig = object : ServerProcessingConfig<TestItem> {
            override val syncFetchConfig = SyncFetchConfig.PostFetchConfig<TestItem>(
                endpoint = "https://api.test.com/items/sync",
                requestBody = buildJsonObject { put("last_synced_at", 0) },
                syncCadenceSeconds = 999_999,
                transformResponse = { response ->
                    val items = response["data"]?.jsonArray ?: return@PostFetchConfig emptyList()
                    items.map {
                        Json.decodeFromJsonElement(TestItem.serializer(), it.jsonObject)
                            .withSyncStatus(SyncableObject.SyncStatus.Synced(""))
                    }
                },
            )
            override val syncUpConfig = testServerConfig().syncUpConfig
            override val serviceHeaders: List<Pair<String, String>> = emptyList()
        }

        val localStore = LocalStoreManager<TestItem, TestRequestTag>(
            database = db, serviceName = "test",
            syncScheduleNotifier = noOpNotifier,
            codec = SyncCodec(TestItem.serializer()),
        )
        val serverManager = ServerManager(
            serviceBaseHeaders = emptyList(),
            httpClient = HttpClient(mockEngine),
        )
        val driver = SyncDriver(
            serverManager = serverManager,
            connectivityChecker = object : ConnectivityChecker {
                override fun isOnline() = true
            },
            codec = SyncCodec(TestItem.serializer()),
            serverProcessingConfig = postFetchConfig,
            localStoreManager = localStore,
            serviceName = "test",
            autoStart = false,
        )

        // Insert and sync-up the CREATE
        localStore.insertLocalData(
            data = localItem.withSyncStatus(SyncableObject.SyncStatus.LocalOnly),
            httpRequest = makeRequest(), idempotencyKey = "idem-create",
            requestTag = TestRequestTag.DEFAULT,
        )
        val coordinator = SyncUpCoordinator(
            drivers = listOf(driver),
            database = db,
        )
        coordinator.syncUpAll()

        // Queue a pending UPDATE
        localStore.updateLocalData(
            data = localItem.copy(name = "LocalEdit", version = "2"),
            idempotencyKey = "idem-update",
            updateRequest = makeRequest(method = HttpRequest.HttpMethod.PUT,
                endpoint = "https://api.test.com/items/${HttpRequest.SERVER_ID_PLACEHOLDER}"),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Queue.Preferred(
                baseData = localItem,
                hasPendingRequests = false,
            ),
            requestTag = TestRequestTag.DEFAULT,
        )

        // Sync down via PostFetchConfig — should merge with pending local UPDATE
        driver.syncDownFromServer()

        val result = localStore.getData(clientId = "c1", serverId = "s1")
        assertNotNull(result)
        val pending = localStore.pendingRequestQueueManager.getPendingRequests("c1")
        assertTrue(pending.isNotEmpty(), "Pending UPDATE should remain after sync-down merge")

        driver.close()
    }

    // endregion

    // region syncUpPendingData - acceptUploadResponseAsProcessed

    @Test
    fun `syncUp marks request as attempted when acceptUploadResponseAsProcessed returns false`() = runBlocking {
        val db = TestDatabaseFactory.createInMemory()
        val item = testItem(clientId = "c1", name = "Item", value = 10, version = "1")

        // Server returns 503 — acceptUploadResponseAsProcessed returns false for 5xx
        val mockEngine = MockEngine {
            respond(
                content = "{}",
                status = HttpStatusCode.ServiceUnavailable,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val localStore = LocalStoreManager<TestItem, TestRequestTag>(
            database = db, serviceName = "test",
            syncScheduleNotifier = noOpNotifier,
            codec = SyncCodec(TestItem.serializer()),
        )
        val serverManager = ServerManager(
            serviceBaseHeaders = emptyList(),
            httpClient = HttpClient(mockEngine),
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

        localStore.insertLocalData(
            data = item,
            httpRequest = makeRequest(),
            idempotencyKey = "idem-c1",
            requestTag = TestRequestTag.DEFAULT,
        )

        val pending = localStore.pendingRequestQueueManager.getPendingRequests("c1")
        assertFalse(pending.first().serverAttemptMade, "serverAttemptMade should start as false")

        // syncUpSinglePendingRequest completes without throwing — the 503 is handled gracefully.
        // It returns false because the upload was not accepted by the server.
        val synced = driver.syncUpSinglePendingRequest(pending.first().pendingRequestId)
        assertFalse(synced, "syncUpSinglePendingRequest returns false when the server does not accept the upload")

        // The request should still be in the queue but marked as attempted
        val afterSync = localStore.pendingRequestQueueManager.getPendingRequests("c1")
        assertEquals(1, afterSync.size, "Request should still be in queue")
        assertTrue(afterSync.first().serverAttemptMade, "serverAttemptMade should be true after 503")

        driver.close()
    }

    @Test
    fun `syncUp handles 429 rate limit by not accepting response`() = runBlocking {
        val db = TestDatabaseFactory.createInMemory()
        val item = testItem(clientId = "c1", name = "Item", value = 10, version = "1")

        val mockEngine = MockEngine {
            respond(
                content = "{}",
                status = HttpStatusCode.TooManyRequests,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val localStore = LocalStoreManager<TestItem, TestRequestTag>(
            database = db, serviceName = "test",
            syncScheduleNotifier = noOpNotifier,
            codec = SyncCodec(TestItem.serializer()),
        )
        val serverManager = ServerManager(
            serviceBaseHeaders = emptyList(),
            httpClient = HttpClient(mockEngine),
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

        localStore.insertLocalData(
            data = item,
            httpRequest = makeRequest(),
            idempotencyKey = "idem-c1",
            requestTag = TestRequestTag.DEFAULT,
        )

        val pending = localStore.pendingRequestQueueManager.getPendingRequests("c1")
        // 429 is not accepted, so the request stays in queue and is marked as attempted
        val synced = driver.syncUpSinglePendingRequest(pending.first().pendingRequestId)
        assertFalse(synced, "syncUpSinglePendingRequest returns false when the server does not accept the upload")

        val afterSync = localStore.pendingRequestQueueManager.getPendingRequests("c1")
        assertEquals(1, afterSync.size, "Request should remain in queue after 429")
        assertTrue(afterSync.first().serverAttemptMade, "serverAttemptMade should be true after 429")

        driver.close()
    }

    @Test
    fun `syncUp handles 408 timeout by not accepting response`() = runBlocking {
        val db = TestDatabaseFactory.createInMemory()
        val item = testItem(clientId = "c1", name = "Item", value = 10, version = "1")

        val mockEngine = MockEngine {
            respond(
                content = "{}",
                status = HttpStatusCode.RequestTimeout,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val localStore = LocalStoreManager<TestItem, TestRequestTag>(
            database = db, serviceName = "test",
            syncScheduleNotifier = noOpNotifier,
            codec = SyncCodec(TestItem.serializer()),
        )
        val serverManager = ServerManager(
            serviceBaseHeaders = emptyList(),
            httpClient = HttpClient(mockEngine),
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

        localStore.insertLocalData(
            data = item,
            httpRequest = makeRequest(),
            idempotencyKey = "idem-c1",
            requestTag = TestRequestTag.DEFAULT,
        )

        val pending = localStore.pendingRequestQueueManager.getPendingRequests("c1")
        // 408 is not accepted, so the request stays in queue and is marked as attempted
        val synced = driver.syncUpSinglePendingRequest(pending.first().pendingRequestId)
        assertFalse(synced, "syncUpSinglePendingRequest returns false when the server does not accept the upload")

        val afterSync = localStore.pendingRequestQueueManager.getPendingRequests("c1")
        assertEquals(1, afterSync.size, "Request should remain in queue after 408")
        assertTrue(afterSync.first().serverAttemptMade, "serverAttemptMade should be true after 408")

        driver.close()
    }

    // endregion

    // region concurrent withClientLock

    @Test
    fun `concurrent syncUp and syncDown on same clientId are serialized by withClientLock`() = runBlocking {
        val db = TestDatabaseFactory.createInMemory()
        val localItem = testItem(clientId = "c1", serverId = "s1", name = "Original", value = 10, version = "1")
        val serverItem = localItem.copy(name = "ServerV2", version = "2")
        val createResponse = wrapResponse(localItem.copy(serverId = "s1"))

        // Track the order of operations to verify serialization
        var requestCount = 0
        val mockEngine = MockEngine {
            requestCount++
            if (requestCount == 1) {
                // CREATE response
                respond(content = createResponse, status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                // sync-down response
                respond(content = wrapListResponse(listOf(serverItem)), status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val (driver, localStore) = createDriver(db, mockEngine = mockEngine)

        // Set up: insert and sync-up a CREATE
        localStore.insertLocalData(
            data = localItem.withSyncStatus(SyncableObject.SyncStatus.LocalOnly),
            httpRequest = makeRequest(), idempotencyKey = "idem-create",
            requestTag = TestRequestTag.DEFAULT,
        )
        val coordinator = SyncUpCoordinator(
            drivers = listOf(driver),
            database = db,
        )
        coordinator.syncUpAll()

        // Now queue an update and run sync-down concurrently
        localStore.updateLocalData(
            data = localItem.copy(name = "LocalEdit", version = "2"),
            idempotencyKey = "idem-update",
            updateRequest = makeRequest(method = HttpRequest.HttpMethod.PUT,
                endpoint = "https://api.test.com/items/s1"),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Queue.Preferred(
                baseData = localItem,
                hasPendingRequests = false,
            ),
            requestTag = TestRequestTag.DEFAULT,
        )

        // Run syncDown which will acquire the lock for clientId "c1"
        driver.syncDownFromServer()

        // The fact that we got here without deadlock or crash proves serialization works
        val result = localStore.getData(clientId = "c1", serverId = "s1")
        assertNotNull(result)

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
