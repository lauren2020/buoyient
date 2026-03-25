package com.les.databuoy

import com.les.databuoy.testing.MockConnectionException
import com.les.databuoy.testing.MockResponse
import com.les.databuoy.testing.TestServiceEnvironment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SyncableObjectServiceTest {

    // region helpers

    private enum class TestRequestTag(override val value: String) : ServiceRequestTag {
        DEFAULT("default"),
        UPDATE("update"),
        VOID("void"),
    }

    private val json = Json { ignoreUnknownKeys = true }

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
        override val globalHeaders: List<Pair<String, String>> = emptyList()
    }

    private fun testItem(
        clientId: String = "client-1",
        serverId: String? = null,
        version: Int = 1,
        name: String = "Test Item",
        value: Int = 0,
        syncStatus: SyncableObject.SyncStatus = SyncableObject.SyncStatus.LocalOnly,
    ) = TestItem(serverId, clientId, version, syncStatus, name, value)

    private fun wrapResponse(item: TestItem): JsonObject = buildJsonObject {
        put("data", json.encodeToJsonElement(TestItem.serializer(), item))
    }

    private class TestItemService(
        serverProcessingConfig: ServerProcessingConfig<TestItem>,
        connectivityChecker: ConnectivityChecker,
        serverManager: ServerManager,
        localStoreManager: LocalStoreManager<TestItem, TestRequestTag>,
    ) : SyncableObjectService<TestItem, TestRequestTag>(
        serializer = TestItem.serializer(),
        serverProcessingConfig = serverProcessingConfig,
        serviceName = "test-items",
        connectivityChecker = connectivityChecker,
        serverManager = serverManager,
        localStoreManager = localStoreManager,
    ) {
        init { stopPeriodicSyncDown() }

        suspend fun testCreate(item: TestItem) = create(
            data = item,
            requestTag = TestRequestTag.DEFAULT,
            request = CreateRequestBuilder { data, idempotencyKey, _, _ ->
                HttpRequest(HttpRequest.HttpMethod.POST, "https://api.test.com/items",
                    buildJsonObject {
                        put("client_id", data.clientId)
                        put("name", data.name)
                        put("idempotency_key", idempotencyKey)
                    })
            },
            unpackSyncData = ResponseUnpacker { body, _, syncStatus ->
                body["data"]?.jsonObject?.let {
                    Json.decodeFromJsonElement(TestItem.serializer(), it).withSyncStatus(syncStatus)
                }
            },
        )

        suspend fun testUpdate(item: TestItem) = update(
            data = item,
            requestTag = TestRequestTag.UPDATE,
            request = UpdateRequestBuilder { _, updated, idempotencyKey, _, _ ->
                HttpRequest(HttpRequest.HttpMethod.PUT,
                    "https://api.test.com/items/${updated.serverId ?: HttpRequest.SERVER_ID_PLACEHOLDER}",
                    buildJsonObject {
                        put("client_id", updated.clientId)
                        put("name", updated.name)
                        put("idempotency_key", idempotencyKey)
                    })
            },
            unpackSyncData = ResponseUnpacker { body, _, syncStatus ->
                body["data"]?.jsonObject?.let {
                    Json.decodeFromJsonElement(TestItem.serializer(), it).withSyncStatus(syncStatus)
                }
            },
        )

        suspend fun testVoid(item: TestItem) = void(
            data = item,
            requestTag = TestRequestTag.VOID,
            request = VoidRequestBuilder { data, _ ->
                HttpRequest(HttpRequest.HttpMethod.DELETE,
                    "https://api.test.com/items/${data.serverId ?: HttpRequest.SERVER_ID_PLACEHOLDER}",
                    JsonObject(emptyMap()))
            },
            unpackData = ResponseUnpacker { body, _, syncStatus ->
                body["data"]?.jsonObject?.let {
                    Json.decodeFromJsonElement(TestItem.serializer(), it).withSyncStatus(syncStatus)
                }
            },
        )

        suspend fun testGet(clientId: String, serverId: String?) = get(
            clientId = clientId,
            serverId = serverId,
            request = HttpRequest(HttpRequest.HttpMethod.GET,
                "https://api.test.com/items/${serverId ?: "unknown"}",
                JsonObject(emptyMap())),
            unpackData = ResponseUnpacker { body, _, syncStatus ->
                body["data"]?.jsonObject?.let {
                    Json.decodeFromJsonElement(TestItem.serializer(), it).withSyncStatus(syncStatus)
                }
            },
        )

        fun testCreateWithFlow(item: TestItem) = createWithFlow(
            data = item,
            requestTag = TestRequestTag.DEFAULT,
            request = CreateRequestBuilder { data, idempotencyKey, _, _ ->
                HttpRequest(HttpRequest.HttpMethod.POST, "https://api.test.com/items",
                    buildJsonObject {
                        put("client_id", data.clientId)
                        put("name", data.name)
                        put("idempotency_key", idempotencyKey)
                    })
            },
            unpackSyncData = ResponseUnpacker { body, _, syncStatus ->
                body["data"]?.jsonObject?.let {
                    Json.decodeFromJsonElement(TestItem.serializer(), it).withSyncStatus(syncStatus)
                }
            },
        )

        fun testUpdateWithFlow(item: TestItem) = updateWithFlow(
            data = item,
            requestTag = TestRequestTag.UPDATE,
            request = UpdateRequestBuilder { _, updated, idempotencyKey, _, _ ->
                HttpRequest(HttpRequest.HttpMethod.PUT,
                    "https://api.test.com/items/${updated.serverId ?: HttpRequest.SERVER_ID_PLACEHOLDER}",
                    buildJsonObject {
                        put("client_id", updated.clientId)
                        put("name", updated.name)
                        put("idempotency_key", idempotencyKey)
                    })
            },
            unpackSyncData = ResponseUnpacker { body, _, syncStatus ->
                body["data"]?.jsonObject?.let {
                    Json.decodeFromJsonElement(TestItem.serializer(), it).withSyncStatus(syncStatus)
                }
            },
        )

        fun testVoidWithFlow(item: TestItem) = voidWithFlow(
            data = item,
            requestTag = TestRequestTag.VOID,
            request = VoidRequestBuilder { data, _ ->
                HttpRequest(HttpRequest.HttpMethod.DELETE,
                    "https://api.test.com/items/${data.serverId ?: HttpRequest.SERVER_ID_PLACEHOLDER}",
                    JsonObject(emptyMap()))
            },
            unpackData = ResponseUnpacker { body, _, syncStatus ->
                body["data"]?.jsonObject?.let {
                    Json.decodeFromJsonElement(TestItem.serializer(), it).withSyncStatus(syncStatus)
                }
            },
        )
    }

    private fun createServiceAndEnv(online: Boolean = true): Pair<TestItemService, TestServiceEnvironment> {
        val env = TestServiceEnvironment()
        env.connectivityChecker.online = online

        env.mockRouter.onGet("https://api.test.com/items") { _ ->
            MockResponse(200, buildJsonObject { put("data", buildJsonObject { }) })
        }

        val localStoreManager = env.createLocalStoreManager<TestItem, TestRequestTag>(
            codec = SyncCodec(TestItem.serializer()), serviceName = "test-items",
        )
        val service = TestItemService(
            serverProcessingConfig = testServerConfig(),
            connectivityChecker = env.connectivityChecker,
            serverManager = env.serverManager,
            localStoreManager = localStoreManager,
        )
        return service to env
    }

    // endregion

    // region create

    @Test
    fun `create online - returns NetworkResponseReceived with server data`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)
        val serverItem = testItem(clientId = "client-1", serverId = "server-1", version = 1)
        env.mockRouter.onPost("https://api.test.com/items") { _ ->
            MockResponse(201, wrapResponse(serverItem))
        }

        val result = service.testCreate(testItem(clientId = "client-1"))

        assertIs<SyncableObjectServiceResponse.Success.NetworkResponseReceived<TestItem>>(result)
        assertEquals(201, result.statusCode)
        assertNotNull(result.updatedData)
        assertEquals("server-1", result.updatedData!!.serverId)
        service.close()
    }

    @Test
    fun `create offline - returns StoredLocally and queues pending request`() = runBlocking {
        val (service, _) = createServiceAndEnv(online = false)

        val result = service.testCreate(testItem(clientId = "client-1"))

        assertIs<SyncableObjectServiceResponse.Success.StoredLocally<TestItem>>(result)
        assertEquals("client-1", result.updatedData.clientId)
        assertEquals(1, service.getAllFromLocalStore().size)
        service.close()
    }

    @Test
    fun `create online with connection error falls back to offline`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)
        env.mockRouter.onPost("https://api.test.com/items") { _ ->
            throw MockConnectionException("Simulated connection error")
        }

        val result = service.testCreate(testItem(clientId = "client-1"))

        assertIs<SyncableObjectServiceResponse.Success.StoredLocally<TestItem>>(result)
        assertEquals(1, service.getAllFromLocalStore().size)
        service.close()
    }

    // endregion

    // region update

    @Test
    fun `update online - returns NetworkResponseReceived`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)

        val serverItem = testItem(clientId = "client-1", serverId = "server-1", version = 1,
            syncStatus = SyncableObject.SyncStatus.Synced("1000"))
        env.mockRouter.onPost("https://api.test.com/items") { _ ->
            MockResponse(201, wrapResponse(serverItem))
        }
        service.testCreate(testItem(clientId = "client-1"))

        val updatedServerItem = serverItem.copy(name = "Updated", version = 2)
        env.mockRouter.onPut("https://api.test.com/items/*") { _ ->
            MockResponse(200, wrapResponse(updatedServerItem))
        }

        val result = service.testUpdate(serverItem.copy(name = "Updated", version = 2))

        assertIs<SyncableObjectServiceResponse.Success.NetworkResponseReceived<TestItem>>(result)
        assertEquals(200, result.statusCode)
        assertEquals("Updated", result.updatedData!!.name)
        service.close()
    }

    @Test
    fun `update offline - queues pending UPDATE`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)

        val serverItem = testItem(clientId = "client-1", serverId = "server-1", version = 1,
            syncStatus = SyncableObject.SyncStatus.Synced("1000"))
        env.mockRouter.onPost("https://api.test.com/items") { _ ->
            MockResponse(201, wrapResponse(serverItem))
        }
        service.testCreate(testItem(clientId = "client-1"))

        env.connectivityChecker.online = false

        val result = service.testUpdate(serverItem.copy(name = "Updated Offline"))

        assertIs<SyncableObjectServiceResponse.Success.StoredLocally<TestItem>>(result)
        assertEquals("Updated Offline", result.updatedData.name)
        service.close()
    }

    // endregion

    // region void

    @Test
    fun `void online - returns NetworkResponseReceived`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)

        val serverItem = testItem(clientId = "client-1", serverId = "server-1", version = 1,
            syncStatus = SyncableObject.SyncStatus.Synced("1000"))
        env.mockRouter.onPost("https://api.test.com/items") { _ ->
            MockResponse(201, wrapResponse(serverItem))
        }
        service.testCreate(testItem(clientId = "client-1"))

        env.mockRouter.onDelete("https://api.test.com/items/*") { _ ->
            MockResponse(200, wrapResponse(serverItem))
        }

        val result = service.testVoid(serverItem)

        assertIs<SyncableObjectServiceResponse.Success.NetworkResponseReceived<TestItem>>(result)
        assertEquals(200, result.statusCode)
        service.close()
    }

    @Test
    fun `void local-only - skips server and marks voided locally`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = false)
        service.testCreate(testItem(clientId = "client-1"))

        env.connectivityChecker.online = true
        val localItem = service.getAllFromLocalStore().first()
        val result = service.testVoid(localItem)

        assertIs<SyncableObjectServiceResponse.Success.StoredLocally<TestItem>>(result)
        service.close()
    }

    // endregion

    // region get

    @Test
    fun `get online - returns ReceivedServerResponse`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)
        val serverItem = testItem(clientId = "client-1", serverId = "server-1", name = "From Server")
        env.mockRouter.onGet("https://api.test.com/items/server-1") { _ ->
            MockResponse(200, wrapResponse(serverItem))
        }

        val result = service.testGet(clientId = "client-1", serverId = "server-1")

        assertIs<GetResponse.ReceivedServerResponse<TestItem>>(result)
        assertEquals(200, result.statusCode)
        assertEquals("From Server", result.data!!.name)
        service.close()
    }

    @Test
    fun `get offline - returns RetrievedFromLocalStore`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)
        val serverItem = testItem(clientId = "client-1", serverId = "server-1",
            syncStatus = SyncableObject.SyncStatus.Synced("1000"))
        env.mockRouter.onPost("https://api.test.com/items") { _ ->
            MockResponse(201, wrapResponse(serverItem))
        }
        service.testCreate(testItem(clientId = "client-1"))

        env.connectivityChecker.online = false
        val result = service.testGet(clientId = "client-1", serverId = "server-1")

        assertIs<GetResponse.RetrievedFromLocalStore<TestItem>>(result)
        assertEquals("client-1", result.data.clientId)
        service.close()
    }

    @Test
    fun `get not found offline - returns NoInternetConnection`() = runBlocking {
        val (service, _) = createServiceAndEnv(online = false)

        val result = service.testGet(clientId = "nonexistent", serverId = null)

        assertIs<GetResponse.NoInternetConnection<TestItem>>(result)
        service.close()
    }

    @Test
    fun `get not found online - returns ReceivedServerResponse`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)
        env.mockRouter.onGet("https://api.test.com/items/null") { _ ->
            MockResponse(statusCode = 404, body = JsonObject(emptyMap()))
        }

        val result = service.testGet(clientId = "nonexistent", serverId = null)

        assertIs<GetResponse.ReceivedServerResponse<TestItem>>(result)
        assertEquals(404, result.statusCode)
        service.close()
    }

    // endregion

    // region getAllFromLocalStore

    @Test
    fun `getAllFromLocalStore returns all items`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)
        val item1 = testItem(clientId = "client-1", serverId = "server-1", name = "Item 1")
        val item2 = testItem(clientId = "client-2", serverId = "server-2", name = "Item 2")

        env.mockRouter.onPost("https://api.test.com/items") { request ->
            val clientId = request.body["client_id"]?.toString()?.trim('"') ?: ""
            val item = if (clientId == "client-1") item1 else item2
            MockResponse(201, wrapResponse(item))
        }

        service.testCreate(testItem(clientId = "client-1", name = "Item 1"))
        service.testCreate(testItem(clientId = "client-2", name = "Item 2"))

        val allItems = service.getAllFromLocalStore()
        assertEquals(2, allItems.size)
        assertTrue(allItems.map { it.clientId }.toSet().containsAll(setOf("client-1", "client-2")))
        service.close()
    }

    // endregion

    // region createWithFlow

    @Test
    fun `createWithFlow emits Loading then Result on success`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)
        val serverItem = testItem(clientId = "client-1", serverId = "server-1", version = 1)
        env.mockRouter.onPost("https://api.test.com/items") { _ ->
            MockResponse(201, wrapResponse(serverItem))
        }

        val flow = service.testCreateWithFlow(testItem(clientId = "client-1"))

        // Flow starts with Loading
        val initial = flow.value
        assertIs<SyncableObjectServiceRequestState.Loading<TestItem>>(initial)

        // Wait for completion
        val result = flow.first { it is SyncableObjectServiceRequestState.Result }
        assertIs<SyncableObjectServiceRequestState.Result<TestItem>>(result)
        assertIs<SyncableObjectServiceResponse.Success.NetworkResponseReceived<TestItem>>(result.response)
        val response = result.response as SyncableObjectServiceResponse.Success.NetworkResponseReceived
        assertEquals(201, response.statusCode)
        assertEquals("server-1", response.updatedData!!.serverId)
        service.close()
    }

    @Test
    fun `createWithFlow emits Result with StoredLocally when offline`() = runBlocking {
        val (service, _) = createServiceAndEnv(online = false)

        val flow = service.testCreateWithFlow(testItem(clientId = "client-1"))

        val result = flow.first { it is SyncableObjectServiceRequestState.Result }
        assertIs<SyncableObjectServiceRequestState.Result<TestItem>>(result)
        assertIs<SyncableObjectServiceResponse.Success.StoredLocally<TestItem>>(result.response)
        service.close()
    }

    // endregion

    // region updateWithFlow

    @Test
    fun `updateWithFlow emits Loading then Result on success`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)

        val serverItem = testItem(clientId = "client-1", serverId = "server-1", version = 1,
            syncStatus = SyncableObject.SyncStatus.Synced("1000"))
        env.mockRouter.onPost("https://api.test.com/items") { _ ->
            MockResponse(201, wrapResponse(serverItem))
        }
        service.testCreate(testItem(clientId = "client-1"))

        val updatedServerItem = serverItem.copy(name = "Updated", version = 2)
        env.mockRouter.onPut("https://api.test.com/items/*") { _ ->
            MockResponse(200, wrapResponse(updatedServerItem))
        }

        val flow = service.testUpdateWithFlow(serverItem.copy(name = "Updated", version = 2))

        val result = flow.first { it is SyncableObjectServiceRequestState.Result }
        assertIs<SyncableObjectServiceRequestState.Result<TestItem>>(result)
        assertIs<SyncableObjectServiceResponse.Success.NetworkResponseReceived<TestItem>>(result.response)
        val response = result.response as SyncableObjectServiceResponse.Success.NetworkResponseReceived
        assertEquals(200, response.statusCode)
        assertEquals("Updated", response.updatedData!!.name)
        service.close()
    }

    // endregion

    // region voidWithFlow

    @Test
    fun `voidWithFlow emits Loading then Result on success`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)

        val serverItem = testItem(clientId = "client-1", serverId = "server-1", version = 1,
            syncStatus = SyncableObject.SyncStatus.Synced("1000"))
        env.mockRouter.onPost("https://api.test.com/items") { _ ->
            MockResponse(201, wrapResponse(serverItem))
        }
        service.testCreate(testItem(clientId = "client-1"))

        env.mockRouter.onDelete("https://api.test.com/items/*") { _ ->
            MockResponse(200, wrapResponse(serverItem))
        }

        val flow = service.testVoidWithFlow(serverItem)

        val result = flow.first { it is SyncableObjectServiceRequestState.Result }
        assertIs<SyncableObjectServiceRequestState.Result<TestItem>>(result)
        assertIs<SyncableObjectServiceResponse.Success.NetworkResponseReceived<TestItem>>(result.response)
        val response = result.response as SyncableObjectServiceResponse.Success.NetworkResponseReceived
        assertEquals(200, response.statusCode)
        service.close()
    }

    @Test
    fun `voidWithFlow local-only emits StoredLocally`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = false)
        service.testCreate(testItem(clientId = "client-1"))

        env.connectivityChecker.online = true
        val localItem = service.getAllFromLocalStore().first()
        val flow = service.testVoidWithFlow(localItem)

        val result = flow.first { it is SyncableObjectServiceRequestState.Result }
        assertIs<SyncableObjectServiceRequestState.Result<TestItem>>(result)
        assertIs<SyncableObjectServiceResponse.Success.StoredLocally<TestItem>>(result.response)
        service.close()
    }

    // endregion
}
