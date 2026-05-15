package com.elvdev.buoyient

import com.elvdev.buoyient.serviceconfigs.ConnectivityChecker
import com.elvdev.buoyient.serviceconfigs.PagingConfig
import com.elvdev.buoyient.serviceconfigs.PendingRequestQueueStrategy
import com.elvdev.buoyient.serviceconfigs.ServerProcessingConfig
import com.elvdev.buoyient.serviceconfigs.SyncFetchConfig
import com.elvdev.buoyient.serviceconfigs.SyncUpConfig
import com.elvdev.buoyient.serviceconfigs.SyncUpResult
import com.elvdev.buoyient.datatypes.CreateRequestBuilder
import com.elvdev.buoyient.datatypes.Filter
import com.elvdev.buoyient.datatypes.GetResponse
import com.elvdev.buoyient.datatypes.HttpRequest
import com.elvdev.buoyient.datatypes.PageCursor
import com.elvdev.buoyient.datatypes.PageDirection
import com.elvdev.buoyient.datatypes.ResponseUnpacker
import com.elvdev.buoyient.datatypes.SquashRequestMerger
import com.elvdev.buoyient.datatypes.SyncableObjectServiceRequestState
import com.elvdev.buoyient.datatypes.SyncableObjectServiceResponse
import com.elvdev.buoyient.datatypes.UpdateRequestBuilder
import com.elvdev.buoyient.datatypes.VoidRequestBuilder
import com.elvdev.buoyient.testing.MockConnectionException
import com.elvdev.buoyient.testing.MockResponse
import com.elvdev.buoyient.testing.MockTimeoutException
import com.elvdev.buoyient.testing.TestServiceEnvironment
import com.elvdev.buoyient.testing.syncUpLocalChanges
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        override val serviceHeaders: List<Pair<String, String>> = emptyList()
    }

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

    private class TestItemService(
        serverProcessingConfig: ServerProcessingConfig<TestItem>,
        connectivityChecker: ConnectivityChecker,
        queueStrategy: PendingRequestQueueStrategy =
            PendingRequestQueueStrategy.Queue,
        pagingConfig: PagingConfig<TestItem> = PagingConfig(keyExtractor = { it.clientId }),
        indexedJsonPaths: List<String> = emptyList(),
    ) : SyncableObjectService<TestItem, TestRequestTag>(
        serializer = TestItem.serializer(),
        serverProcessingConfig = serverProcessingConfig,
        serviceName = "test-items",
        connectivityChecker = connectivityChecker,
        queueStrategy = queueStrategy,
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
                    })
            },
            unpackSyncData = ResponseUnpacker { body, _, syncStatus ->
                body["data"]?.jsonObject?.let {
                    Json.decodeFromJsonElement(TestItem.serializer(), it).withSyncStatus(syncStatus)
                }
            },
        )

        suspend fun testUpdate(
            item: TestItem,
            constraints: ProcessingConstraints = ProcessingConstraints.NoConstraints,
        ) = update(
            data = item,
            processingConstraints = constraints,
            requestTag = TestRequestTag.UPDATE,
            request = UpdateRequestBuilder { _, updated, idempotencyKey, _, _ ->
                HttpRequest(
                    HttpRequest.HttpMethod.PUT,
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

        suspend fun testCreateWithCrossServicePlaceholder(
            item: TestItem,
            constraints: ProcessingConstraints = ProcessingConstraints.NoConstraints,
        ) = create(
            data = item,
            processingConstraints = constraints,
            requestTag = TestRequestTag.DEFAULT,
            request = CreateRequestBuilder { data, idempotencyKey, _, _ ->
                HttpRequest(
                    HttpRequest.HttpMethod.POST, "https://api.test.com/items",
                    buildJsonObject {
                        put("client_id", data.clientId)
                        put(
                            "order_id",
                            HttpRequest.crossServiceServerIdPlaceholder("orders", "order-1")
                        )
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

        suspend fun testVoid(item: TestItem) = void(
            data = item,
            requestTag = TestRequestTag.VOID,
            request = VoidRequestBuilder { data, _, _ ->
                HttpRequest(
                    HttpRequest.HttpMethod.DELETE,
                    "https://api.test.com/items/${data.serverId ?: HttpRequest.SERVER_ID_PLACEHOLDER}",
                    JsonObject(emptyMap())
                )
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
            request = HttpRequest(
                HttpRequest.HttpMethod.GET,
                "https://api.test.com/items/${serverId ?: "unknown"}",
                JsonObject(emptyMap())
            ),
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
                HttpRequest(
                    HttpRequest.HttpMethod.POST, "https://api.test.com/items",
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
                HttpRequest(
                    HttpRequest.HttpMethod.PUT,
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
            request = VoidRequestBuilder { data, _, _ ->
                HttpRequest(
                    HttpRequest.HttpMethod.DELETE,
                    "https://api.test.com/items/${data.serverId ?: HttpRequest.SERVER_ID_PLACEHOLDER}",
                    JsonObject(emptyMap())
                )
            },
            unpackData = ResponseUnpacker { body, _, syncStatus ->
                body["data"]?.jsonObject?.let {
                    Json.decodeFromJsonElement(TestItem.serializer(), it).withSyncStatus(syncStatus)
                }
            },
        )
    }

    private fun createServiceAndEnv(
        online: Boolean = true,
        queueStrategy: PendingRequestQueueStrategy =
            PendingRequestQueueStrategy.Queue,
    ): Pair<TestItemService, TestServiceEnvironment> {
        val env = TestServiceEnvironment()
        env.connectivityChecker.online = online

        env.mockRouter.onGet("https://api.test.com/items") { _ ->
            MockResponse(200, buildJsonObject { put("data", buildJsonObject { }) })
        }

        val service = TestItemService(
            serverProcessingConfig = testServerConfig(),
            connectivityChecker = env.connectivityChecker,
            queueStrategy = queueStrategy,
        )
        return service to env
    }

    // endregion

    // region create

    @Test
    fun `create online - returns NetworkResponseReceived with server data`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)
        val serverItem = testItem(clientId = "client-1", serverId = "server-1", version = "1")
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

    // -- Helper to seed a synced item in the db via online create --

    private suspend fun seedSyncedItem(
        service: TestItemService,
        env: TestServiceEnvironment,
        clientId: String = "client-1",
        serverId: String = "server-1",
        name: String = "Test Item",
        version: String? = "1",
    ): TestItem {
        val serverItem = testItem(clientId = clientId, serverId = serverId, version = version,
            name = name, syncStatus = SyncableObject.SyncStatus.Synced("1000"))
        env.mockRouter.onPost("https://api.test.com/items") { _ ->
            MockResponse(201, wrapResponse(serverItem))
        }
        service.testCreate(testItem(clientId = clientId, name = name))
        return serverItem
    }

    // -- Online (NoConstraints, connectivity = true, no pending requests) --

    @Test
    fun `update online - returns NetworkResponseReceived`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)
        val serverItem = seedSyncedItem(service, env)

        val updatedServerItem = serverItem.copy(name = "Updated", version = "2")
        env.mockRouter.onPut("https://api.test.com/items/*") { _ ->
            MockResponse(200, wrapResponse(updatedServerItem))
        }

        val result = service.testUpdate(serverItem.copy(name = "Updated", version = "2"))

        assertIs<SyncableObjectServiceResponse.Success.NetworkResponseReceived<TestItem>>(result)
        assertEquals(200, result.statusCode)
        assertEquals("Updated", result.updatedData!!.name)
        service.close()
    }

    @Test
    fun `update online - db reflects server response data`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)
        val serverItem = seedSyncedItem(service, env)

        val updatedServerItem = serverItem.copy(name = "Server Name", version = "2")
        env.mockRouter.onPut("https://api.test.com/items/*") { _ ->
            MockResponse(200, wrapResponse(updatedServerItem))
        }

        service.testUpdate(serverItem.copy(name = "Server Name", version = "2"))

        val stored = service.getAllFromLocalStore()
        assertEquals(1, stored.size)
        assertEquals("Server Name", stored[0].name)
        assertEquals("2", stored[0].version)
        service.close()
    }

    @Test
    fun `update online - no pending requests remain after success`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)
        val serverItem = seedSyncedItem(service, env)

        val updatedServerItem = serverItem.copy(name = "Updated", version = "2")
        env.mockRouter.onPut("https://api.test.com/items/*") { _ ->
            MockResponse(200, wrapResponse(updatedServerItem))
        }

        service.testUpdate(serverItem.copy(name = "Updated", version = "2"))

        val pendingRequests = env.database.syncPendingEventsQueries
            .getPendingRequestsByClientId("test-items", "client-1")
            .executeAsList()
        assertTrue(pendingRequests.isEmpty())
        service.close()
    }

    // -- Offline (NoConstraints, connectivity = false) --

    @Test
    fun `update offline - queues pending UPDATE`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)
        val serverItem = seedSyncedItem(service, env)

        env.connectivityChecker.online = false

        val result = service.testUpdate(serverItem.copy(name = "Updated Offline"))

        assertIs<SyncableObjectServiceResponse.Success.StoredLocally<TestItem>>(result)
        assertEquals("Updated Offline", result.updatedData.name)
        service.close()
    }

    @Test
    fun `update offline - db data updated and pending request queued`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)
        val serverItem = seedSyncedItem(service, env)

        env.connectivityChecker.online = false
        service.testUpdate(serverItem.copy(name = "Offline Name"))

        val stored = service.getAllFromLocalStore()
        assertEquals(1, stored.size)
        assertEquals("Offline Name", stored[0].name)

        val pendingRequests = env.database.syncPendingEventsQueries
            .getPendingRequestsByClientId("test-items", "client-1")
            .executeAsList()
        assertEquals(1, pendingRequests.size)
        assertEquals("UPDATE", pendingRequests[0].type)
        service.close()
    }

    // -- ProcessingConstraints.OfflineOnly (forces async even when online) --

    @Test
    fun `update with OfflineOnly constraint - queues async even when online`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)
        val serverItem = seedSyncedItem(service, env)

        // Server should not be called — no PUT handler registered
        val result = service.testUpdate(
            serverItem.copy(name = "Forced Offline"),
            constraints = SyncableObjectService.ProcessingConstraints.OfflineOnly,
        )

        assertIs<SyncableObjectServiceResponse.Success.StoredLocally<TestItem>>(result)
        assertEquals("Forced Offline", result.updatedData.name)

        val pendingRequests = env.database.syncPendingEventsQueries
            .getPendingRequestsByClientId("test-items", "client-1")
            .executeAsList()
        assertEquals(1, pendingRequests.size)
        service.close()
    }

    // -- ProcessingConstraints.OnlineOnly --

    @Test
    fun `update with OnlineOnly constraint - succeeds when online`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)
        val serverItem = seedSyncedItem(service, env)

        val updatedServerItem = serverItem.copy(name = "Online Only", version = "2")
        env.mockRouter.onPut("https://api.test.com/items/*") { _ ->
            MockResponse(200, wrapResponse(updatedServerItem))
        }

        val result = service.testUpdate(
            serverItem.copy(name = "Online Only", version = "2"),
            constraints = SyncableObjectService.ProcessingConstraints.OnlineOnly,
        )

        assertIs<SyncableObjectServiceResponse.Success.NetworkResponseReceived<TestItem>>(result)
        assertEquals("Online Only", result.updatedData!!.name)
        service.close()
    }

    @Test
    fun `update with OnlineOnly constraint offline - connection error returns NoInternetConnection`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)
        val serverItem = seedSyncedItem(service, env)

        // OnlineOnly enters the online branch regardless of connectivity state.
        // Simulate connection failure so updateSync gets a ConnectionError.
        env.connectivityChecker.online = false
        env.mockRouter.onPut("https://api.test.com/items/*") { _ ->
            throw MockConnectionException()
        }

        val result = service.testUpdate(
            serverItem.copy(name = "Will Fail"),
            constraints = SyncableObjectService.ProcessingConstraints.OnlineOnly,
        )

        assertIs<SyncableObjectServiceResponse.NoInternetConnection<TestItem>>(result)

        // DB should remain unchanged
        val stored = service.getAllFromLocalStore()
        assertEquals(1, stored.size)
        assertEquals("Test Item", stored[0].name)
        service.close()
    }

    @Test
    fun `update with OnlineOnly constraint - returns InvalidRequest when pending requests exist`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)
        val serverItem = seedSyncedItem(service, env)

        // Create a pending request by going offline and doing an update
        env.connectivityChecker.online = false
        service.testUpdate(serverItem.copy(name = "Pending Update"))
        env.connectivityChecker.online = true

        val result = service.testUpdate(
            serverItem.copy(name = "OnlineOnly After Pending"),
            constraints = SyncableObjectService.ProcessingConstraints.OnlineOnly,
        )

        assertIs<SyncableObjectServiceResponse.InvalidRequest<TestItem>>(result)
        service.close()
    }

    // -- Online with pending requests (forces async to preserve ordering) --

    @Test
    fun `update online with pending requests - queues async to preserve order`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)
        val serverItem = seedSyncedItem(service, env)

        // Create a pending request by going offline
        env.connectivityChecker.online = false
        service.testUpdate(serverItem.copy(name = "First Offline"))
        env.connectivityChecker.online = true

        // Now update again online — should be forced async
        val result = service.testUpdate(serverItem.copy(name = "Second Online"))

        assertIs<SyncableObjectServiceResponse.Success.StoredLocally<TestItem>>(result)

        val pendingRequests = env.database.syncPendingEventsQueries
            .getPendingRequestsByClientId("test-items", "client-1")
            .executeAsList()
        assertEquals(2, pendingRequests.size)
        service.close()
    }

    // -- Connection error fallback --

    @Test
    fun `update online with connection error - falls back to async`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)
        val serverItem = seedSyncedItem(service, env)

        env.mockRouter.onPut("https://api.test.com/items/*") { _ ->
            throw MockConnectionException()
        }

        val result = service.testUpdate(serverItem.copy(name = "Conn Error"))

        assertIs<SyncableObjectServiceResponse.Success.StoredLocally<TestItem>>(result)
        assertEquals("Conn Error", result.updatedData.name)

        val pendingRequests = env.database.syncPendingEventsQueries
            .getPendingRequestsByClientId("test-items", "client-1")
            .executeAsList()
        assertEquals(1, pendingRequests.size)
        assertEquals(0L, pendingRequests[0].server_attempt_made)
        service.close()
    }

    @Test
    fun `update OnlineOnly with connection error - returns NoInternetConnection`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)
        val serverItem = seedSyncedItem(service, env)

        env.mockRouter.onPut("https://api.test.com/items/*") { _ ->
            throw MockConnectionException()
        }

        val result = service.testUpdate(
            serverItem.copy(name = "No Fallback"),
            constraints = SyncableObjectService.ProcessingConstraints.OnlineOnly,
        )

        assertIs<SyncableObjectServiceResponse.NoInternetConnection<TestItem>>(result)

        // No pending request should be created for OnlineOnly
        val pendingRequests = env.database.syncPendingEventsQueries
            .getPendingRequestsByClientId("test-items", "client-1")
            .executeAsList()
        assertTrue(pendingRequests.isEmpty())
        service.close()
    }

    // -- Timeout fallback --

    @Test
    fun `update online with timeout - falls back to async with server attempt flag`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)
        val serverItem = seedSyncedItem(service, env)

        env.mockRouter.onPut("https://api.test.com/items/*") { _ ->
            throw MockTimeoutException()
        }

        val result = service.testUpdate(serverItem.copy(name = "Timed Out"))

        assertIs<SyncableObjectServiceResponse.Success.StoredLocally<TestItem>>(result)

        val pendingRequests = env.database.syncPendingEventsQueries
            .getPendingRequestsByClientId("test-items", "client-1")
            .executeAsList()
        assertEquals(1, pendingRequests.size)
        assertEquals(1L, pendingRequests[0].server_attempt_made)
        service.close()
    }

    @Test
    fun `update OnlineOnly with timeout - returns RequestTimedOut`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)
        val serverItem = seedSyncedItem(service, env)

        env.mockRouter.onPut("https://api.test.com/items/*") { _ ->
            throw MockTimeoutException()
        }

        val result = service.testUpdate(
            serverItem.copy(name = "No Fallback Timeout"),
            constraints = SyncableObjectService.ProcessingConstraints.OnlineOnly,
        )

        assertIs<SyncableObjectServiceResponse.RequestTimedOut<TestItem>>(result)

        val pendingRequests = env.database.syncPendingEventsQueries
            .getPendingRequestsByClientId("test-items", "client-1")
            .executeAsList()
        assertTrue(pendingRequests.isEmpty())
        service.close()
    }

    // -- Server error (5xx) --

    @Test
    fun `update online with server error - returns ServerError`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)
        val serverItem = seedSyncedItem(service, env)

        env.mockRouter.onPut("https://api.test.com/items/*") { _ ->
            MockResponse(500, buildJsonObject { put("error", "Internal Server Error") })
        }

        val result = service.testUpdate(serverItem.copy(name = "Server Fail"))

        assertIs<SyncableObjectServiceResponse.ServerError<TestItem>>(result)
        assertEquals(500, result.statusCode)

        // DB should not be updated
        val stored = service.getAllFromLocalStore()
        assertEquals("Test Item", stored[0].name)

        // No pending request queued for server errors
        val pendingRequests = env.database.syncPendingEventsQueries
            .getPendingRequestsByClientId("test-items", "client-1")
            .executeAsList()
        assertTrue(pendingRequests.isEmpty())
        service.close()
    }

    // -- Client error (4xx) --

    @Test
    fun `update online with 4xx error - returns Failed NetworkResponseReceived`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)
        val serverItem = seedSyncedItem(service, env)

        env.mockRouter.onPut("https://api.test.com/items/*") { _ ->
            MockResponse(422, buildJsonObject { put("error", "Validation failed") })
        }

        val result = service.testUpdate(serverItem.copy(name = "Bad Request"))

        assertIs<SyncableObjectServiceResponse.Failed.NetworkResponseReceived<TestItem>>(result)
        assertEquals(422, result.statusCode)

        // DB should not be updated
        val stored = service.getAllFromLocalStore()
        assertEquals("Test Item", stored[0].name)
        service.close()
    }

    // -- Invalid DB state (no existing data to update) --

    @Test
    fun `update on item not in db - returns InvalidRequest`() = runBlocking {
        val (service, _) = createServiceAndEnv(online = true)

        val orphanItem = testItem(clientId = "nonexistent", serverId = "s-1", version = "1",
            syncStatus = SyncableObject.SyncStatus.Synced("1000"))

        val result = service.testUpdate(orphanItem.copy(name = "Ghost"))

        assertIs<SyncableObjectServiceResponse.InvalidRequest<TestItem>>(result)
        service.close()
    }

    // -- Queue strategy: Queue (default) -- multiple offline updates create separate entries --

    @Test
    fun `update offline Queue strategy - multiple updates create separate pending requests`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true, queueStrategy = PendingRequestQueueStrategy.Queue)
        val serverItem = seedSyncedItem(service, env)

        env.connectivityChecker.online = false

        service.testUpdate(serverItem.copy(name = "Edit 1"))
        service.testUpdate(serverItem.copy(name = "Edit 2"))
        service.testUpdate(serverItem.copy(name = "Edit 3"))

        val pendingRequests = env.database.syncPendingEventsQueries
            .getPendingRequestsByClientId("test-items", "client-1")
            .executeAsList()
        assertEquals(3, pendingRequests.size)

        // DB reflects the latest update
        val stored = service.getAllFromLocalStore()
        assertEquals("Edit 3", stored[0].name)
        service.close()
    }

    // -- Queue strategy: Squash -- consecutive offline updates squash into one --

    @Test
    fun `update offline Squash strategy - consecutive updates squash into one pending request`() = runBlocking {
        val (service, env) = createServiceAndEnv(
            online = true,
            queueStrategy = PendingRequestQueueStrategy.Squash(
                squashUpdateIntoCreate = SquashRequestMerger { createReq, updateReq ->
                    // Merge the update body into the create request
                    HttpRequest(createReq.method, createReq.endpointUrl, updateReq.requestBody)
                },
            ),
        )
        val serverItem = seedSyncedItem(service, env)

        env.connectivityChecker.online = false

        service.testUpdate(serverItem.copy(name = "Squash 1"))
        service.testUpdate(serverItem.copy(name = "Squash 2"))
        service.testUpdate(serverItem.copy(name = "Squash 3"))

        val pendingRequests = env.database.syncPendingEventsQueries
            .getPendingRequestsByClientId("test-items", "client-1")
            .executeAsList()
        // Squash strategy collapses consecutive updates (no server attempt) into one
        assertEquals(1, pendingRequests.size)

        // DB reflects the latest update
        val stored = service.getAllFromLocalStore()
        assertEquals("Squash 3", stored[0].name)
        service.close()
    }

    @Test
    fun `update Squash strategy - timeout update not squashed with subsequent update`() = runBlocking {
        val (service, env) = createServiceAndEnv(
            online = true,
            queueStrategy = PendingRequestQueueStrategy.Squash(
                squashUpdateIntoCreate = SquashRequestMerger { createReq, updateReq ->
                    HttpRequest(createReq.method, createReq.endpointUrl, updateReq.requestBody)
                },
            ),
        )
        val serverItem = seedSyncedItem(service, env)

        // First update times out — gets queued with serverAttemptMade = true
        env.mockRouter.onPut("https://api.test.com/items/*") { _ ->
            throw MockTimeoutException()
        }
        service.testUpdate(serverItem.copy(name = "Timeout Edit"))

        // Second update offline — should NOT squash into the timed-out one
        env.connectivityChecker.online = false
        service.testUpdate(serverItem.copy(name = "After Timeout"))

        val pendingRequests = env.database.syncPendingEventsQueries
            .getPendingRequestsByClientId("test-items", "client-1")
            .executeAsList()
        // Timed-out request is preserved separately since server may have processed it
        assertEquals(2, pendingRequests.size)
        assertEquals(1L, pendingRequests[0].server_attempt_made)
        assertEquals(0L, pendingRequests[1].server_attempt_made)
        service.close()
    }

    // -- Squash strategy: offline create then update squashes into the create --

    @Test
    fun `update Squash strategy - update after offline create squashes into create`() = runBlocking {
        val (service, env) = createServiceAndEnv(
            online = false,
            queueStrategy = PendingRequestQueueStrategy.Squash(
                squashUpdateIntoCreate = SquashRequestMerger { createReq, updateReq ->
                    HttpRequest(createReq.method, createReq.endpointUrl, updateReq.requestBody)
                },
            ),
        )

        // Offline create — queues a pending CREATE
        service.testCreate(testItem(clientId = "client-1", name = "Original"))

        // Now update the locally-created item — should squash into the pending CREATE
        val localItem = service.getAllFromLocalStore().first()
        val result = service.testUpdate(localItem.copy(name = "Squashed Into Create"))

        assertIs<SyncableObjectServiceResponse.Success.StoredLocally<TestItem>>(result)
        assertEquals("Squashed Into Create", result.updatedData.name)

        val pendingRequests = env.database.syncPendingEventsQueries
            .getPendingRequestsByClientId("test-items", "client-1")
            .executeAsList()
        // Should still be just one pending request (the squashed create)
        assertEquals(1, pendingRequests.size)
        assertEquals("CREATE", pendingRequests[0].type)
        service.close()
    }

    // endregion

    // region void

    @Test
    fun `void online - returns NetworkResponseReceived`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)

        val serverItem = testItem(clientId = "client-1", serverId = "server-1", version = "1",
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
        val serverItem = testItem(clientId = "client-1", serverId = "server-1", version = "1")
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

        val serverItem = testItem(clientId = "client-1", serverId = "server-1", version = "1",
            syncStatus = SyncableObject.SyncStatus.Synced("1000"))
        env.mockRouter.onPost("https://api.test.com/items") { _ ->
            MockResponse(201, wrapResponse(serverItem))
        }
        service.testCreate(testItem(clientId = "client-1"))

        val updatedServerItem = serverItem.copy(name = "Updated", version = "2")
        env.mockRouter.onPut("https://api.test.com/items/*") { _ ->
            MockResponse(200, wrapResponse(updatedServerItem))
        }

        val flow = service.testUpdateWithFlow(serverItem.copy(name = "Updated", version = "2"))

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

        val serverItem = testItem(clientId = "client-1", serverId = "server-1", version = "1",
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

    // region getFromLocalStore (filtering)

    @Test
    fun `getFromLocalStore with syncStatus filter returns only matching items`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)
        // Seed a synced item
        seedSyncedItem(service, env, clientId = "client-1", serverId = "server-1", name = "Synced Item")

        // Create an offline-only item
        env.connectivityChecker.online = false
        service.testCreate(testItem(clientId = "client-2", name = "Offline Item"))

        val syncedOnly = service.getFromLocalStore(syncStatus = SyncableObject.SyncStatus.SYNCED)
        assertEquals(1, syncedOnly.size)
        assertEquals("Synced Item", syncedOnly[0].name)

        val pendingCreateOnly = service.getFromLocalStore(syncStatus = SyncableObject.SyncStatus.PENDING_CREATE)
        assertEquals(1, pendingCreateOnly.size)
        assertEquals("Offline Item", pendingCreateOnly[0].name)

        service.close()
    }

    @Test
    fun `getFromLocalStore with includeVoided false excludes voided items`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)

        val activeItem = testItem(clientId = "client-1", serverId = "server-1", name = "Active",
            syncStatus = SyncableObject.SyncStatus.Synced("1000"))
        val voidedItem = testItem(clientId = "client-2", serverId = "server-2", name = "Voided",
            syncStatus = SyncableObject.SyncStatus.Synced("1000"))

        env.mockRouter.onPost("https://api.test.com/items") { request ->
            val clientId = request.body["client_id"]?.toString()?.trim('"') ?: ""
            val item = if (clientId == "client-1") activeItem else voidedItem
            MockResponse(201, wrapResponse(item))
        }
        service.testCreate(testItem(clientId = "client-1", name = "Active"))
        service.testCreate(testItem(clientId = "client-2", name = "Voided"))

        env.mockRouter.onDelete("https://api.test.com/items/*") { _ ->
            MockResponse(200, wrapResponse(voidedItem.copy(version = "2")))
        }
        service.testVoid(voidedItem)

        val nonVoided = service.getFromLocalStore(includeVoided = false)
        assertEquals(1, nonVoided.size)
        assertEquals("Active", nonVoided[0].name)

        val all = service.getFromLocalStore(includeVoided = true)
        assertEquals(2, all.size)

        service.close()
    }

    @Test
    fun `getFromLocalStore with predicate filters by domain field`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)
        val item1 = testItem(clientId = "client-1", serverId = "server-1", name = "Alpha", value = 10,
            syncStatus = SyncableObject.SyncStatus.Synced("1000"))
        val item2 = testItem(clientId = "client-2", serverId = "server-2", name = "Beta", value = 20,
            syncStatus = SyncableObject.SyncStatus.Synced("1000"))

        env.mockRouter.onPost("https://api.test.com/items") { request ->
            val clientId = request.body["client_id"]?.toString()?.trim('"') ?: ""
            val item = if (clientId == "client-1") item1 else item2
            MockResponse(201, wrapResponse(item))
        }
        service.testCreate(testItem(clientId = "client-1", name = "Alpha", value = 10))
        service.testCreate(testItem(clientId = "client-2", name = "Beta", value = 20))

        val highValue = service.getFromLocalStore(predicate = { it.value >= 15 })
        assertEquals(1, highValue.size)
        assertEquals("Beta", highValue[0].name)

        service.close()
    }

    @Test
    fun `getFromLocalStoreAsFlow with syncStatus filter emits filtered list`() = runBlocking {
        val (service, _) = createServiceAndEnv(online = false)
        service.testCreate(testItem(clientId = "client-1", name = "Offline"))

        val items = service.getFromLocalStoreAsFlow(syncStatus = SyncableObject.SyncStatus.PENDING_CREATE).first()
        assertEquals(1, items.size)
        assertEquals("Offline", items[0].name)

        val syncedItems = service.getFromLocalStoreAsFlow(syncStatus = SyncableObject.SyncStatus.SYNCED).first()
        assertEquals(0, syncedItems.size)

        service.close()
    }

    @Test
    fun `getFromLocalStoreAsFlow with predicate filters reactively`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)
        val item1 = testItem(clientId = "client-1", serverId = "server-1", name = "Keep", value = 100,
            syncStatus = SyncableObject.SyncStatus.Synced("1000"))
        val item2 = testItem(clientId = "client-2", serverId = "server-2", name = "Skip", value = 1,
            syncStatus = SyncableObject.SyncStatus.Synced("1000"))

        env.mockRouter.onPost("https://api.test.com/items") { request ->
            val clientId = request.body["client_id"]?.toString()?.trim('"') ?: ""
            val item = if (clientId == "client-1") item1 else item2
            MockResponse(201, wrapResponse(item))
        }
        service.testCreate(testItem(clientId = "client-1", name = "Keep", value = 100))
        service.testCreate(testItem(clientId = "client-2", name = "Skip", value = 1))

        val items = service.getFromLocalStoreAsFlow(predicate = { it.value > 50 }).first()
        assertEquals(1, items.size)
        assertEquals("Keep", items[0].name)

        service.close()
    }

    // endregion

    // region placeholder resolution in sync path

    @Test
    fun `updateSync resolves serverId placeholder in URL from baseData`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)
        // First create an item so it has a serverId in the local store.
        val serverItem = testItem(clientId = "client-1", serverId = "server-1", version = "1")
        env.mockRouter.onPost("https://api.test.com/items") { _ ->
            MockResponse(201, wrapResponse(serverItem))
        }
        service.testCreate(testItem(clientId = "client-1"))

        // Now update — the request builder uses SERVER_ID_PLACEHOLDER in the URL.
        // The placeholder should be resolved from baseData.serverId before sending.
        var capturedUrl: String? = null
        val updatedServerItem = testItem(clientId = "client-1", serverId = "server-1", version = "2", name = "Updated")
        env.mockRouter.onPut("https://api.test.com/items/server-1") { request ->
            capturedUrl = request.url
            MockResponse(200, wrapResponse(updatedServerItem))
        }

        val result = service.testUpdate(
            serverItem.copy(name = "Updated").withSyncStatus(SyncableObject.SyncStatus.Synced("")),
        )

        assertIs<SyncableObjectServiceResponse.Success.NetworkResponseReceived<TestItem>>(result)
        assertNotNull(capturedUrl)
        assertFalse(capturedUrl!!.contains(HttpRequest.SERVER_ID_PLACEHOLDER), "URL should not contain placeholder")
        assertTrue(capturedUrl!!.contains("server-1"), "URL should contain resolved server ID")
        service.close()
    }

    @Test
    fun `voidSync resolves serverId placeholder in URL from data`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)
        // Create an item first.
        val serverItem = testItem(clientId = "client-1", serverId = "server-1", version = "1")
        env.mockRouter.onPost("https://api.test.com/items") { _ ->
            MockResponse(201, wrapResponse(serverItem))
        }
        service.testCreate(testItem(clientId = "client-1"))

        // Void it — URL uses SERVER_ID_PLACEHOLDER pattern, should be resolved.
        var capturedUrl: String? = null
        val voidedItem = testItem(clientId = "client-1", serverId = "server-1", version = "1")
        env.mockRouter.onDelete("https://api.test.com/items/server-1") { request ->
            capturedUrl = request.url
            MockResponse(200, wrapResponse(voidedItem))
        }

        val result = service.testVoid(
            serverItem.withSyncStatus(SyncableObject.SyncStatus.Synced("")),
        )

        assertIs<SyncableObjectServiceResponse.Success.NetworkResponseReceived<TestItem>>(result)
        assertNotNull(capturedUrl)
        assertFalse(capturedUrl!!.contains(HttpRequest.SERVER_ID_PLACEHOLDER), "URL should not contain placeholder")
        service.close()
    }

    @Test
    fun `createSync with cross-service placeholder - resolves when dependency is synced`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)

        // Insert a synced order into the DB so cross-service resolution can find it.
        env.database.syncDataQueries.insertFromServerResponse(
            service_name = "orders",
            client_id = "order-1",
            server_id = "server-order-42",
            version = "1",
            data_blob = "{}",
            last_synced_timestamp = "2024-01-01",
            sync_status = SyncableObject.SyncStatus.SYNCED,
            last_synced_server_data = "{}",
            paging_key = null,
        )

        // Now create an item whose request body contains a cross-service placeholder.
        var capturedBody: String? = null
        val serverItem = testItem(clientId = "client-1", serverId = "server-1", version = "1")
        env.mockRouter.onPost("https://api.test.com/items") { request ->
            capturedBody = request.body.toString()
            MockResponse(201, wrapResponse(serverItem))
        }

        val result = service.testCreateWithCrossServicePlaceholder(testItem(clientId = "client-1"))

        assertIs<SyncableObjectServiceResponse.Success.NetworkResponseReceived<TestItem>>(result)
        assertNotNull(capturedBody)
        assertTrue(capturedBody!!.contains("server-order-42"), "Body should contain resolved cross-service ID")
        assertFalse(capturedBody!!.contains("{cross:"), "Body should not contain unresolved placeholder")
        service.close()
    }

    @Test
    fun `createSync with unresolved cross-service placeholder and OnlineOnly returns InvalidRequest`() = runBlocking {
        val (service, _) = createServiceAndEnv(online = true)

        // Don't insert any "orders" data — the cross-service placeholder will be unresolvable.
        val result = service.testCreateWithCrossServicePlaceholder(
            testItem(clientId = "client-1"),
            constraints = SyncableObjectService.ProcessingConstraints.OnlineOnly,
        )

        assertIs<SyncableObjectServiceResponse.InvalidRequest<TestItem>>(result)
        service.close()
    }

    @Test
    fun `createSync with unresolved cross-service placeholder falls back to async`() = runBlocking {
        val (service, _) = createServiceAndEnv(online = true)

        // Don't insert any "orders" data — the cross-service placeholder will be unresolvable.
        // With NoConstraints, it should fall back to async (stored locally).
        val result = service.testCreateWithCrossServicePlaceholder(testItem(clientId = "client-1"))

        assertIs<SyncableObjectServiceResponse.Success.StoredLocally<TestItem>>(result)
        service.close()
    }

    // endregion

    // region server client_id mismatch — no duplication

    @Test
    fun `create online - server returning different clientId does not create duplicate`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)
        // Server response has a different client_id than what we sent
        val serverItem = testItem(
            clientId = "server-assigned-id", serverId = "server-1", version = "1",
            name = "Created",
        )
        env.mockRouter.onPost("https://api.test.com/items") { _ ->
            MockResponse(201, wrapResponse(serverItem))
        }

        val result = service.testCreate(testItem(clientId = "client-1", name = "Created"))

        assertIs<SyncableObjectServiceResponse.Success.NetworkResponseReceived<TestItem>>(result)
        // Should have exactly 1 item, stored under the original client_id
        val allItems = service.getAllFromLocalStore()
        assertEquals(1, allItems.size, "Should have 1 row, not a duplicate")
        service.close()
    }

    @Test
    fun `update online - server returning different clientId updates correct row`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)
        val seeded = seedSyncedItem(service, env, clientId = "client-1", serverId = "server-1")

        // Server returns updated item with a different client_id
        val serverUpdated = testItem(
            clientId = "server-assigned-id", serverId = "server-1", version = "2",
            name = "Updated",
        )
        env.mockRouter.onPut("https://api.test.com/items/server-1") { _ ->
            MockResponse(200, wrapResponse(serverUpdated))
        }

        val result = service.testUpdate(seeded.copy(name = "Updated"))

        assertIs<SyncableObjectServiceResponse.Success.NetworkResponseReceived<TestItem>>(result)
        val allItems = service.getAllFromLocalStore()
        assertEquals(1, allItems.size, "Should have 1 row, not a duplicate")
        service.close()
    }

    @Test
    fun `void online - server returning different clientId updates correct row`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = true)
        val seeded = seedSyncedItem(service, env, clientId = "client-1", serverId = "server-1")

        // Server returns voided item with a different client_id
        val serverVoided = testItem(
            clientId = "server-assigned-id", serverId = "server-1", version = "2",
            name = "Test Item",
        )
        env.mockRouter.onDelete("https://api.test.com/items/server-1") { _ ->
            MockResponse(200, wrapResponse(serverVoided))
        }

        val result = service.testVoid(seeded)

        assertIs<SyncableObjectServiceResponse.Success.NetworkResponseReceived<TestItem>>(result)
        val allItems = service.getAllFromLocalStore()
        assertEquals(1, allItems.size, "Should have 1 row, not a duplicate")
        service.close()
    }

    // endregion

    // region sync-up: offline create then update

    /**
     * Reproduces a bug where sync_data is stuck at PENDING_UPDATE after
     * offline create + offline update + sync up.
     *
     * Root cause: when `fromResponseBody` deserializes the server response
     * into a data object whose `clientId` differs from the original (e.g.,
     * the server doesn't echo `client_id` and the model's default generates
     * a new UUID), the 3-way merge rebase picks up the wrong `clientId` on
     * the pending UPDATE. The subsequent `upsertEntry` then targets the
     * wrong row, leaving the original row stuck at PENDING_UPDATE.
     *
     * Sequence:
     *   1. Device offline — create item (clientId = "client-1")
     *   2. Device offline — update item (clientId = "client-1")
     *   3. Device online — sync up
     *   4. CREATE uploaded — server response has clientId = "server-assigned-client"
     *      (simulates a server that doesn't echo the original client_id)
     *   5. Rebase of UPDATE picks up the wrong clientId from the server data
     *   6. UPDATE uploaded — upsertEntry targets the wrong row
     *   7. BUG: original sync_data row for "client-1" stuck at PENDING_UPDATE
     */
    @Test
    fun `offline create then update - server response with different clientId - status transitions to SYNCED`() = runBlocking {
        val (service, env) = createServiceAndEnv(online = false)

        val item = testItem(clientId = "client-1", name = "Original", value = 10)

        // Server responses have a DIFFERENT clientId than the original —
        // this simulates APIs (like Square) where the response JSON does not
        // include the original client_id field, causing deserialization to
        // generate a new default value.
        val serverCreated = testItem(
            clientId = "server-assigned-client", serverId = "server-1",
            version = "1", name = "Original", value = 10,
        )
        val serverUpdated = testItem(
            clientId = "server-assigned-client-2", serverId = "server-1",
            version = "2", name = "Updated", value = 20,
        )

        // Step 1: Offline create
        val createResult = service.testCreate(item)
        assertIs<SyncableObjectServiceResponse.Success.StoredLocally<TestItem>>(createResult)

        // Step 2: Offline update
        val updatedItem = createResult.updatedData.copy(name = "Updated", value = 20, version = "2")
        val updateResult = service.testUpdate(updatedItem)
        assertIs<SyncableObjectServiceResponse.Success.StoredLocally<TestItem>>(updateResult)

        // Verify we have 2 pending requests
        val pendingBefore = env.database.syncPendingEventsQueries
            .getPendingRequestsByClientId("test-items", "client-1")
            .executeAsList()
        assertEquals(2, pendingBefore.size, "Should have 2 pending requests (CREATE + UPDATE)")

        // Set up mock responses
        env.mockRouter.onPost("https://api.test.com/items") { _ ->
            MockResponse(201, wrapResponse(serverCreated))
        }
        env.mockRouter.onPut("https://api.test.com/items/*") { _ ->
            MockResponse(200, wrapResponse(serverUpdated))
        }

        // Step 3: Bring online and sync
        env.connectivityChecker.online = true
        val syncedCount = service.syncUpLocalChanges()

        // Assert: both requests processed
        assertEquals(2, syncedCount, "Both CREATE and UPDATE should have been synced")

        // Assert: no pending requests remain for the original client_id
        val pendingAfter = env.database.syncPendingEventsQueries
            .getPendingRequestsByClientId("test-items", "client-1")
            .executeAsList()
        assertEquals(0, pendingAfter.size, "No pending requests should remain")

        // Assert: sync_data status is SYNCED for the ORIGINAL client_id
        val syncDataRow = env.database.syncDataQueries
            .getData("test-items", "client-1", "server-1")
            .executeAsOne()
        assertEquals(
            SyncableObject.SyncStatus.SYNCED,
            syncDataRow.sync_status,
            "sync_data entry for original client_id should be SYNCED, but was: ${syncDataRow.sync_status}"
        )

        service.close()
    }

    // endregion

    // region loadPage

    private fun createPagingServiceAndEnv(
        online: Boolean = true,
        sortOrder: PagingConfig.SortOrder = PagingConfig.SortOrder.DESC,
    ): Pair<TestItemService, TestServiceEnvironment> {
        val env = TestServiceEnvironment()
        env.connectivityChecker.online = online
        env.mockRouter.onGet("https://api.test.com/items") { _ ->
            MockResponse(200, buildJsonObject { put("data", buildJsonObject { }) })
        }
        val service = TestItemService(
            serverProcessingConfig = testServerConfig(),
            connectivityChecker = env.connectivityChecker,
            pagingConfig = PagingConfig(
                keyExtractor = { it.name },
                sortOrder = sortOrder,
            ),
        )
        return service to env
    }

    @Test
    fun `loadPage - DESC default returns items newest-first by paging key`() = runBlocking {
        val (service, _) = createPagingServiceAndEnv(online = false)
        service.testCreate(testItem(clientId = "c1", name = "Apple"))
        service.testCreate(testItem(clientId = "c2", name = "Mango"))
        service.testCreate(testItem(clientId = "c3", name = "Banana"))

        val result = service.loadPage(loadSize = 10)

        assertEquals(3, result.items.size)
        assertEquals(listOf("Mango", "Banana", "Apple"), result.items.map { it.name })
        // Fewer items than loadSize → no more pages.
        assertEquals(null, result.nextCursor)
        service.close()
    }

    @Test
    fun `loadPage - ASC returns items oldest-first by paging key`() = runBlocking {
        val (service, _) = createPagingServiceAndEnv(
            online = false,
            sortOrder = PagingConfig.SortOrder.ASC,
        )
        service.testCreate(testItem(clientId = "c1", name = "Apple"))
        service.testCreate(testItem(clientId = "c2", name = "Mango"))
        service.testCreate(testItem(clientId = "c3", name = "Banana"))

        val result = service.loadPage(loadSize = 10)

        assertEquals(listOf("Apple", "Banana", "Mango"), result.items.map { it.name })
        service.close()
    }

    @Test
    fun `loadPage - subsequent page resumes from nextCursor`() = runBlocking {
        val (service, _) = createPagingServiceAndEnv(
            online = false,
            sortOrder = PagingConfig.SortOrder.ASC,
        )
        service.testCreate(testItem(clientId = "c1", name = "Apple"))
        service.testCreate(testItem(clientId = "c2", name = "Banana"))
        service.testCreate(testItem(clientId = "c3", name = "Cherry"))
        service.testCreate(testItem(clientId = "c4", name = "Date"))

        val firstPage = service.loadPage(loadSize = 2)
        assertEquals(listOf("Apple", "Banana"), firstPage.items.map { it.name })
        assertNotNull(firstPage.nextCursor)
        assertEquals("Banana", firstPage.nextCursor!!.key)
        assertEquals("c2", firstPage.nextCursor!!.clientId)

        val secondPage = service.loadPage(direction = PageDirection.Forward(firstPage.nextCursor!!), loadSize = 2)
        assertEquals(listOf("Cherry", "Date"), secondPage.items.map { it.name })

        // Past the end — empty page, no more cursor.
        val thirdPage = service.loadPage(direction = PageDirection.Forward(secondPage.nextCursor!!), loadSize = 2)
        assertTrue(thirdPage.items.isEmpty())
        assertEquals(null, thirdPage.nextCursor)
        service.close()
    }

    @Test
    fun `loadPage - tiebreaker on client_id when paging keys collide (ASC)`() = runBlocking {
        // Three items share the same paging key — the composite cursor should
        // tiebreak on client_id so no rows are skipped.
        val (service, _) = createPagingServiceAndEnv(
            online = false,
            sortOrder = PagingConfig.SortOrder.ASC,
        )
        service.testCreate(testItem(clientId = "c1", name = "Same"))
        service.testCreate(testItem(clientId = "c2", name = "Same"))
        service.testCreate(testItem(clientId = "c3", name = "Same"))

        val firstPage = service.loadPage(loadSize = 2)
        assertEquals(listOf("c1", "c2"), firstPage.items.map { it.clientId })
        assertNotNull(firstPage.nextCursor)
        assertEquals("Same", firstPage.nextCursor!!.key)
        assertEquals("c2", firstPage.nextCursor!!.clientId)

        val secondPage = service.loadPage(direction = PageDirection.Forward(firstPage.nextCursor!!), loadSize = 2)
        assertEquals(listOf("c3"), secondPage.items.map { it.clientId })
        service.close()
    }

    @Test
    fun `loadPage - tiebreaker on client_id when paging keys collide (DESC)`() = runBlocking {
        val (service, _) = createPagingServiceAndEnv(online = false)
        service.testCreate(testItem(clientId = "c1", name = "Same"))
        service.testCreate(testItem(clientId = "c2", name = "Same"))
        service.testCreate(testItem(clientId = "c3", name = "Same"))

        val firstPage = service.loadPage(loadSize = 2)
        // DESC tiebreaks on client_id descending too: c3, c2.
        assertEquals(listOf("c3", "c2"), firstPage.items.map { it.clientId })

        val secondPage = service.loadPage(direction = PageDirection.Forward(firstPage.nextCursor!!), loadSize = 2)
        assertEquals(listOf("c1"), secondPage.items.map { it.clientId })
        service.close()
    }

    @Test
    fun `loadPage - respects loadSize and reports nextCursor when full`() = runBlocking {
        val (service, _) = createPagingServiceAndEnv(
            online = false,
            sortOrder = PagingConfig.SortOrder.ASC,
        )
        service.testCreate(testItem(clientId = "c1", name = "Alpha"))
        service.testCreate(testItem(clientId = "c2", name = "Beta"))
        service.testCreate(testItem(clientId = "c3", name = "Gamma"))

        val page = service.loadPage(loadSize = 2)

        assertEquals(2, page.items.size)
        assertEquals(listOf("Alpha", "Beta"), page.items.map { it.name })
        assertNotNull(page.nextCursor)
        service.close()
    }

    @Test
    fun `loadPage - default config pages by clientId`() = runBlocking {
        // Service constructed without an explicit pagingConfig — default extractor
        // is { it.clientId }, so pages come back ordered by clientId DESC.
        val (service, _) = createServiceAndEnv(online = false)
        service.testCreate(testItem(clientId = "c1", name = "A"))
        service.testCreate(testItem(clientId = "c2", name = "B"))
        service.testCreate(testItem(clientId = "c3", name = "C"))

        val result = service.loadPage(loadSize = 10)

        // DESC by clientId.
        assertEquals(listOf("c3", "c2", "c1"), result.items.map { it.clientId })
        service.close()
    }

    // endregion

    // region loadPage backward

    @Test
    fun `loadPage - Backward returns items before cursor in sort order (ASC)`() = runBlocking {
        val (service, _) = createPagingServiceAndEnv(
            online = false,
            sortOrder = PagingConfig.SortOrder.ASC,
        )
        service.testCreate(testItem(clientId = "c1", name = "Apple"))
        service.testCreate(testItem(clientId = "c2", name = "Banana"))
        service.testCreate(testItem(clientId = "c3", name = "Cherry"))
        service.testCreate(testItem(clientId = "c4", name = "Date"))

        // Load forward to get a mid-list cursor (after "Banana"), then page backward
        // from "Date" — should return Cherry then walk back via prevCursor.
        val mid = service.loadPage(loadSize = 2)
        val midCursor = mid.nextCursor!! // Banana's cursor
        val pageBeforeDate = service.loadPage(
            direction = PageDirection.Forward(midCursor),
            loadSize = 1,
        )
        // pageBeforeDate has Cherry. Use its nextCursor (Cherry) as a Backward boundary.
        val backCursor = pageBeforeDate.nextCursor!! // Cherry's cursor

        val backward = service.loadPage(
            direction = PageDirection.Backward(backCursor),
            loadSize = 10,
        )

        // Items strictly before Cherry in ASC order: Apple, Banana. Returned in sort order.
        assertEquals(listOf("Apple", "Banana"), backward.items.map { it.name })
        // We hit the head, so prevCursor = null. There's stuff after (Cherry itself), so nextCursor != null.
        assertEquals(null, backward.prevCursor)
        assertEquals("Banana", backward.nextCursor!!.key)
        service.close()
    }

    @Test
    fun `loadPage - Backward returns items before cursor in sort order (DESC)`() = runBlocking {
        val (service, _) = createPagingServiceAndEnv(online = false)  // DESC default
        service.testCreate(testItem(clientId = "c1", name = "Apple"))
        service.testCreate(testItem(clientId = "c2", name = "Banana"))
        service.testCreate(testItem(clientId = "c3", name = "Cherry"))
        service.testCreate(testItem(clientId = "c4", name = "Date"))

        // DESC order is Date, Cherry, Banana, Apple. Page backward from Banana —
        // "before" in DESC sort means Date, Cherry.
        val firstPage = service.loadPage(loadSize = 3)
        // firstPage = Date, Cherry, Banana. nextCursor = Banana's cursor.
        val bananaCursor = firstPage.nextCursor!!

        val backward = service.loadPage(
            direction = PageDirection.Backward(bananaCursor),
            loadSize = 10,
        )

        assertEquals(listOf("Date", "Cherry"), backward.items.map { it.name })
        assertEquals(null, backward.prevCursor)  // hit head (DESC head = Date)
        service.close()
    }

    @Test
    fun `loadPage - Backward partial page signals head with null prevCursor`() = runBlocking {
        val (service, _) = createPagingServiceAndEnv(
            online = false,
            sortOrder = PagingConfig.SortOrder.ASC,
        )
        service.testCreate(testItem(clientId = "c1", name = "Apple"))
        service.testCreate(testItem(clientId = "c2", name = "Banana"))

        // Backward from Banana with large loadSize — returns just Apple and signals head.
        val bananaCursor = PageCursor(key = "Banana", clientId = "c2")

        val backward = service.loadPage(
            direction = PageDirection.Backward(bananaCursor),
            loadSize = 10,
        )

        assertEquals(listOf("Apple"), backward.items.map { it.name })
        assertEquals(null, backward.prevCursor)
        assertEquals("Apple", backward.nextCursor!!.key)
        service.close()
    }

    @Test
    fun `loadPage - Backward full page reports prevCursor for further backward paging`() = runBlocking {
        val (service, _) = createPagingServiceAndEnv(
            online = false,
            sortOrder = PagingConfig.SortOrder.ASC,
        )
        service.testCreate(testItem(clientId = "c1", name = "Apple"))
        service.testCreate(testItem(clientId = "c2", name = "Banana"))
        service.testCreate(testItem(clientId = "c3", name = "Cherry"))
        service.testCreate(testItem(clientId = "c4", name = "Date"))

        // Backward from Date with loadSize=2: returns Banana, Cherry. Full page → prevCursor non-null.
        val backward = service.loadPage(
            direction = PageDirection.Backward(PageCursor(key = "Date", clientId = "c4")),
            loadSize = 2,
        )
        assertEquals(listOf("Banana", "Cherry"), backward.items.map { it.name })
        assertEquals("Banana", backward.prevCursor!!.key)

        // Walk further backward from prevCursor — should return Apple, then hit head.
        val backward2 = service.loadPage(
            direction = PageDirection.Backward(backward.prevCursor!!),
            loadSize = 2,
        )
        assertEquals(listOf("Apple"), backward2.items.map { it.name })
        assertEquals(null, backward2.prevCursor)
        service.close()
    }

    @Test
    fun `loadPage - Forward from cursor reports prevCursor pointing back to that page`() = runBlocking {
        val (service, _) = createPagingServiceAndEnv(
            online = false,
            sortOrder = PagingConfig.SortOrder.ASC,
        )
        service.testCreate(testItem(clientId = "c1", name = "Apple"))
        service.testCreate(testItem(clientId = "c2", name = "Banana"))
        service.testCreate(testItem(clientId = "c3", name = "Cherry"))
        service.testCreate(testItem(clientId = "c4", name = "Date"))

        val firstPage = service.loadPage(loadSize = 2)
        // FromHead → prevCursor must be null (nothing before the head).
        assertEquals(null, firstPage.prevCursor)

        val secondPage = service.loadPage(
            direction = PageDirection.Forward(firstPage.nextCursor!!),
            loadSize = 2,
        )
        // Forward from a cursor → prevCursor points at the first item of this page,
        // so a backward load from it returns the previous page.
        assertEquals("Cherry", secondPage.prevCursor!!.key)
        val backward = service.loadPage(
            direction = PageDirection.Backward(secondPage.prevCursor!!),
            loadSize = 10,
        )
        assertEquals(listOf("Apple", "Banana"), backward.items.map { it.name })
        service.close()
    }

    @Test
    fun `loadPage - Backward composes with syncStatus filter`() = runBlocking {
        val (service, env) = createPagingServiceAndEnv(
            online = false,
            sortOrder = PagingConfig.SortOrder.ASC,
        )
        // c1, c3 PENDING_CREATE; c2 SYNCED.
        service.testCreate(testItem(clientId = "c1", name = "Apple"))
        env.database.syncDataQueries.insertFromServerResponse(
            service_name = "test-items",
            client_id = "c2",
            server_id = "server-c2",
            version = "1",
            last_synced_timestamp = "2024-01-01",
            data_blob = json.encodeToString(
                TestItem.serializer(),
                testItem(clientId = "c2", serverId = "server-c2", name = "Banana"),
            ),
            sync_status = SyncableObject.SyncStatus.SYNCED,
            last_synced_server_data = "{}",
            paging_key = "Banana",
        )
        service.testCreate(testItem(clientId = "c3", name = "Cherry"))

        val backward = service.loadPage(
            direction = PageDirection.Backward(PageCursor(key = "Cherry", clientId = "c3")),
            loadSize = 10,
            syncStatus = SyncableObject.SyncStatus.PENDING_CREATE,
        )

        // Only Apple satisfies both "before Cherry" and PENDING_CREATE.
        assertEquals(listOf("Apple"), backward.items.map { it.name })
        service.close()
    }

    @Test
    fun `loadPage - Backward composes with Filter predicate`() = runBlocking {
        val (service, _) = createFilterServiceAndEnv()  // ASC by name
        service.testCreate(testItem(clientId = "c1", name = "Apple", value = 1))
        service.testCreate(testItem(clientId = "c2", name = "Banana", value = 2))
        service.testCreate(testItem(clientId = "c3", name = "Cherry", value = 1))
        service.testCreate(testItem(clientId = "c4", name = "Date", value = 1))

        // Backward from Date with value=1 filter — Banana is excluded by filter,
        // so we should get Apple and Cherry in ASC order.
        val backward = service.loadPage(
            direction = PageDirection.Backward(PageCursor(key = "Date", clientId = "c4")),
            loadSize = 10,
            filter = Filter.eq("$.value", 1),
        )
        assertEquals(listOf("Apple", "Cherry"), backward.items.map { it.name })
        service.close()
    }

    // endregion

    // region loadPage with Filter

    private fun createFilterServiceAndEnv(
        sortOrder: PagingConfig.SortOrder = PagingConfig.SortOrder.ASC,
        indexedJsonPaths: List<String> = emptyList(),
    ): Pair<TestItemService, TestServiceEnvironment> {
        val env = TestServiceEnvironment()
        env.connectivityChecker.online = false  // keep all writes local
        env.mockRouter.onGet("https://api.test.com/items") { _ ->
            MockResponse(200, buildJsonObject { put("data", buildJsonObject { }) })
        }
        val service = TestItemService(
            serverProcessingConfig = testServerConfig(),
            connectivityChecker = env.connectivityChecker,
            pagingConfig = PagingConfig(
                keyExtractor = { it.name },
                sortOrder = sortOrder,
            ),
            indexedJsonPaths = indexedJsonPaths,
        )
        return service to env
    }

    @Test
    fun `loadPage with Eq filter returns only matching rows`() = runBlocking {
        val (service, _) = createFilterServiceAndEnv()
        service.testCreate(testItem(clientId = "c1", name = "Alpha", value = 1))
        service.testCreate(testItem(clientId = "c2", name = "Beta", value = 2))
        service.testCreate(testItem(clientId = "c3", name = "Gamma", value = 1))

        val result = service.loadPage(
            loadSize = 10,
            filter = Filter.eq("$.value", 1),
        )

        assertEquals(listOf("Alpha", "Gamma"), result.items.map { it.name })
        service.close()
    }

    @Test
    fun `loadPage with Gt filter does numeric comparison`() = runBlocking {
        val (service, _) = createFilterServiceAndEnv()
        service.testCreate(testItem(clientId = "c1", name = "A", value = 2))
        service.testCreate(testItem(clientId = "c2", name = "B", value = 10))
        service.testCreate(testItem(clientId = "c3", name = "C", value = 5))

        // If json_extract returned TEXT, "10" < "2" lexicographically and we'd
        // miss the value-10 row. Verifying numeric semantics here.
        val result = service.loadPage(
            loadSize = 10,
            filter = Filter.gt("$.value", 4),
        )

        assertEquals(setOf("B", "C"), result.items.map { it.name }.toSet())
        service.close()
    }

    @Test
    fun `loadPage with And filter requires all clauses`() = runBlocking {
        val (service, _) = createFilterServiceAndEnv()
        service.testCreate(testItem(clientId = "c1", name = "Active1", value = 5))
        service.testCreate(testItem(clientId = "c2", name = "Active2", value = 1))
        service.testCreate(testItem(clientId = "c3", name = "Other", value = 5))

        val result = service.loadPage(
            loadSize = 10,
            filter = Filter.and(
                Filter.like("$.name", "Active%"),
                Filter.gte("$.value", 3),
            ),
        )

        assertEquals(listOf("Active1"), result.items.map { it.name })
        service.close()
    }

    @Test
    fun `loadPage with Or filter matches any clause`() = runBlocking {
        val (service, _) = createFilterServiceAndEnv()
        service.testCreate(testItem(clientId = "c1", name = "Apple", value = 1))
        service.testCreate(testItem(clientId = "c2", name = "Banana", value = 2))
        service.testCreate(testItem(clientId = "c3", name = "Cherry", value = 3))

        val result = service.loadPage(
            loadSize = 10,
            filter = Filter.or(
                Filter.eq("$.name", "Apple"),
                Filter.eq("$.name", "Cherry"),
            ),
        )

        assertEquals(setOf("Apple", "Cherry"), result.items.map { it.name }.toSet())
        service.close()
    }

    @Test
    fun `loadPage with Not filter inverts predicate`() = runBlocking {
        val (service, _) = createFilterServiceAndEnv()
        service.testCreate(testItem(clientId = "c1", name = "A", value = 1))
        service.testCreate(testItem(clientId = "c2", name = "B", value = 2))
        service.testCreate(testItem(clientId = "c3", name = "C", value = 3))

        val result = service.loadPage(
            loadSize = 10,
            filter = Filter.not(Filter.eq("$.value", 2)),
        )

        assertEquals(setOf("A", "C"), result.items.map { it.name }.toSet())
        service.close()
    }

    @Test
    fun `loadPage with In filter matches any value in list`() = runBlocking {
        val (service, _) = createFilterServiceAndEnv()
        service.testCreate(testItem(clientId = "c1", name = "A", value = 1))
        service.testCreate(testItem(clientId = "c2", name = "B", value = 2))
        service.testCreate(testItem(clientId = "c3", name = "C", value = 3))
        service.testCreate(testItem(clientId = "c4", name = "D", value = 4))

        val result = service.loadPage(
            loadSize = 10,
            filter = Filter.isIn("$.value", listOf(1, 3)),
        )

        assertEquals(setOf("A", "C"), result.items.map { it.name }.toSet())
        service.close()
    }

    @Test
    fun `loadPage with In filter empty list returns no rows`() = runBlocking {
        val (service, _) = createFilterServiceAndEnv()
        service.testCreate(testItem(clientId = "c1", name = "A", value = 1))

        val result = service.loadPage(
            loadSize = 10,
            filter = Filter.isIn("$.value", emptyList()),
        )

        assertTrue(result.items.isEmpty())
        service.close()
    }

    @Test
    fun `loadPage filter composes with cursor pagination`() = runBlocking {
        val (service, _) = createFilterServiceAndEnv()
        service.testCreate(testItem(clientId = "c1", name = "Apple", value = 1))
        service.testCreate(testItem(clientId = "c2", name = "Banana", value = 2))
        service.testCreate(testItem(clientId = "c3", name = "Cherry", value = 1))
        service.testCreate(testItem(clientId = "c4", name = "Date", value = 1))
        service.testCreate(testItem(clientId = "c5", name = "Elderberry", value = 1))

        val firstPage = service.loadPage(
            loadSize = 2,
            filter = Filter.eq("$.value", 1),
        )
        assertEquals(listOf("Apple", "Cherry"), firstPage.items.map { it.name })
        assertNotNull(firstPage.nextCursor)

        val secondPage = service.loadPage(
            direction = PageDirection.Forward(firstPage.nextCursor!!),
            loadSize = 2,
            filter = Filter.eq("$.value", 1),
        )
        // Banana (value=2) is skipped by the filter; Date and Elderberry remain.
        assertEquals(listOf("Date", "Elderberry"), secondPage.items.map { it.name })
        service.close()
    }

    @Test
    fun `loadPage filter composes with syncStatus`() = runBlocking {
        val (service, env) = createFilterServiceAndEnv()
        // Seed a mix: c1 PENDING_CREATE (value=1), c2 SYNCED (value=1), c3 PENDING_CREATE (value=2).
        service.testCreate(testItem(clientId = "c1", name = "A", value = 1))
        env.database.syncDataQueries.insertFromServerResponse(
            service_name = "test-items",
            client_id = "c2",
            server_id = "server-c2",
            version = "1",
            last_synced_timestamp = "2024-01-01",
            data_blob = json.encodeToString(
                TestItem.serializer(),
                testItem(clientId = "c2", serverId = "server-c2", name = "B", value = 1),
            ),
            sync_status = SyncableObject.SyncStatus.SYNCED,
            last_synced_server_data = "{}",
            paging_key = "B",
        )
        service.testCreate(testItem(clientId = "c3", name = "C", value = 2))

        val result = service.loadPage(
            loadSize = 10,
            syncStatus = SyncableObject.SyncStatus.PENDING_CREATE,
            filter = Filter.eq("$.value", 1),
        )

        // Only c1 satisfies both PENDING_CREATE AND value=1.
        assertEquals(listOf("A"), result.items.map { it.name })
        service.close()
    }

    @Test
    fun `loadPage filter no matches returns empty page with null cursor`() = runBlocking {
        val (service, _) = createFilterServiceAndEnv()
        service.testCreate(testItem(clientId = "c1", name = "A", value = 1))

        val result = service.loadPage(
            loadSize = 10,
            filter = Filter.eq("$.value", 999),
        )

        assertTrue(result.items.isEmpty())
        assertEquals(null, result.nextCursor)
        service.close()
    }

    @Test
    fun `indexedJsonPaths creates SQLite expression indexes`() = runBlocking {
        val (service, env) = createFilterServiceAndEnv(
            indexedJsonPaths = listOf("$.value", "$.name"),
        )
        // Trigger lazy index creation by running any filter query.
        service.testCreate(testItem(clientId = "c1", name = "A", value = 1))
        service.loadPage(loadSize = 1, filter = Filter.eq("$.value", 1))

        val indexNames = env.databaseHandle.driver.executeQuery(
            identifier = null,
            sql = "SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'idx_test_items_%'",
            mapper = { cursor ->
                val names = mutableListOf<String>()
                while (cursor.next().value) {
                    names += cursor.getString(0)!!
                }
                app.cash.sqldelight.db.QueryResult.Value(names.toList())
            },
            parameters = 0,
        ).value

        assertTrue(indexNames.any { it.contains("value") }, "expected value index, got: $indexNames")
        assertTrue(indexNames.any { it.contains("name") }, "expected name index, got: $indexNames")
        service.close()
    }

    @Test
    fun `indexedJsonPaths rejects malformed paths`() = runBlocking {
        val (service, _) = createFilterServiceAndEnv(
            indexedJsonPaths = listOf("not-a-valid-path"),
        )
        service.testCreate(testItem(clientId = "c1", name = "A", value = 1))

        var threw = false
        try {
            service.loadPage(loadSize = 1, filter = Filter.eq("$.value", 1))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
        service.close()
    }

    // endregion
}
