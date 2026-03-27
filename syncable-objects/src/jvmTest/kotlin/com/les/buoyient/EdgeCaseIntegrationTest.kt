package com.les.buoyient

import com.les.buoyient.db.SyncDatabase
import com.les.buoyient.managers.LocalStoreManager
import com.les.buoyient.managers.PendingRequestQueueManager
import com.les.buoyient.managers.PendingSyncRequest
import com.les.buoyient.managers.ServerManager
import com.les.buoyient.serviceconfigs.ConnectivityChecker
import com.les.buoyient.serviceconfigs.EncryptionProvider
import com.les.buoyient.serviceconfigs.PendingRequestQueueStrategy
import com.les.buoyient.serviceconfigs.ServerProcessingConfig
import com.les.buoyient.serviceconfigs.SyncFetchConfig
import com.les.buoyient.serviceconfigs.SyncUpConfig
import com.les.buoyient.serviceconfigs.SyncUpResult
import com.les.buoyient.sync.SyncDriver
import com.les.buoyient.sync.SyncScheduleNotifier
import com.les.buoyient.sync.SyncUpCoordinator
import com.les.buoyient.datatypes.HttpRequest
import com.les.buoyient.datatypes.SquashRequestMerger
import com.les.buoyient.testing.NoOpSyncScheduleNotifier
import com.les.buoyient.testing.TestDatabaseFactory
import com.les.buoyient.utils.SyncCodec
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for edge cases and cross-feature interactions that are not
 * covered by the focused unit-style tests. Each test exercises a multi-step
 * scenario that spans multiple subsystems (e.g., sync-up + conflict resolution,
 * encryption + squash, cross-service + failure).
 */
class EdgeCaseIntegrationTest {

    // region helpers

    private enum class TestRequestTag(override val value: String) : ServiceRequestTag {
        DEFAULT("default"),
        UPDATE("update"),
        VOID("void"),
    }

    private val noOpNotifier: SyncScheduleNotifier = NoOpSyncScheduleNotifier
    private val json = Json { ignoreUnknownKeys = true }

    private val offlineChecker = object : ConnectivityChecker {
        override fun isOnline(): Boolean = false
    }

    private fun testItem(
        clientId: String,
        serverId: String? = null,
        version: String? = "1",
        name: String = "Test",
        value: Int = 0,
        syncStatus: SyncableObject.SyncStatus = SyncableObject.SyncStatus.LocalOnly,
        tags: List<String> = emptyList(),
    ) = TestItem(serverId, clientId, version, syncStatus, name, value, tags)

    private fun makeRequest(
        method: HttpRequest.HttpMethod = HttpRequest.HttpMethod.POST,
        endpoint: String = "https://api.test.com/items",
        body: JsonObject = JsonObject(emptyMap()),
    ) = HttpRequest(method = method, endpointUrl = endpoint, requestBody = body)

    private fun wrapResponse(item: TestItem): String = buildJsonObject {
        put("data", json.encodeToJsonElement(TestItem.serializer(), item))
    }.toString()

    private fun wrapListResponse(items: List<TestItem>): String = buildJsonObject {
        put("data", Json.encodeToJsonElement(
            kotlinx.serialization.builtins.ListSerializer(TestItem.serializer()), items
        ))
    }.toString()

    private fun testServerConfig(
        transformResponse: (JsonObject) -> List<TestItem> = { emptyList() },
    ) = object : ServerProcessingConfig<TestItem> {
        override val syncFetchConfig = SyncFetchConfig.GetFetchConfig<TestItem>(
            endpoint = "https://api.test.com/items",
            syncCadenceSeconds = 999_999,
            transformResponse = transformResponse,
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

    private fun testServerConfigWithSyncDown() = testServerConfig(
        transformResponse = { response ->
            val items = response["data"]?.jsonArray ?: return@testServerConfig emptyList()
            items.map {
                Json.decodeFromJsonElement(TestItem.serializer(), it.jsonObject)
                    .withSyncStatus(SyncableObject.SyncStatus.Synced(""))
            }
        },
    )

    private fun createDriver(
        serviceName: String,
        database: SyncDatabase,
        mockEngine: MockEngine,
        serverConfig: ServerProcessingConfig<TestItem> = testServerConfig(),
        connectivityChecker: ConnectivityChecker = offlineChecker,
    ): Pair<SyncDriver<TestItem, TestRequestTag>, LocalStoreManager<TestItem, TestRequestTag>> {
        val localStore = LocalStoreManager<TestItem, TestRequestTag>(
            database = database,
            serviceName = serviceName,
            syncScheduleNotifier = noOpNotifier,
            codec = SyncCodec(TestItem.serializer()),
        )
        val serverManager = ServerManager(
            serviceBaseHeaders = emptyList(),
            httpClient = HttpClient(mockEngine),
        )
        val driver = SyncDriver(
            serverManager = serverManager,
            connectivityChecker = connectivityChecker,
            codec = SyncCodec(TestItem.serializer()),
            serverProcessingConfig = serverConfig,
            localStoreManager = localStore,
            serviceName = serviceName,
            autoStart = false,
        )
        return driver to localStore
    }

    private suspend fun syncUpViaCoordinator(
        drivers: List<SyncDriver<TestItem, TestRequestTag>>,
        database: SyncDatabase,
    ): Int = SyncUpCoordinator(drivers, database).syncUpAll()

    @OptIn(ExperimentalEncodingApi::class)
    private class TestEncryptionProvider : EncryptionProvider {
        val prefix = "ENC:"
        override fun encrypt(plaintext: String): String =
            prefix + Base64.encode(plaintext.encodeToByteArray())
        override fun decrypt(ciphertext: String): String {
            require(ciphertext.startsWith(prefix)) { "Not encrypted: $ciphertext" }
            return Base64.decode(ciphertext.removePrefix(prefix)).decodeToString()
        }
    }

    // endregion

    // -----------------------------------------------------------------------
    // 1. Timeout on CREATE → offline UPDATE before retry
    // -----------------------------------------------------------------------

    /**
     * Scenario:
     *   1. CREATE times out online → queued with serverAttemptMade = true
     *   2. Go offline, issue UPDATE on the same object
     *   3. Come back online, sync-up runs
     *
     * Expected: Both the CREATE and UPDATE sync successfully. The CREATE
     * is sent first (with serverAttemptMade flag), then the UPDATE is
     * rebased with the server-assigned serverId and sent.
     */
    @Test
    fun `timeout on CREATE followed by offline UPDATE syncs both correctly`() = runBlocking {
        val db = TestDatabaseFactory.createInMemory()

        val createData = testItem(clientId = "c1", name = "Original", value = 10)
        val updateData = createData.copy(name = "Updated", version = "2")
        val serverCreateResponse = createData.copy(serverId = "server-1")
        val serverUpdateResponse = updateData.copy(serverId = "server-1")

        var requestCount = 0
        val mockEngine = MockEngine {
            requestCount++
            val responseItem = if (requestCount == 1) serverCreateResponse else serverUpdateResponse
            respond(
                content = wrapResponse(responseItem),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val localStore = LocalStoreManager<TestItem, TestRequestTag>(
            database = db,
            serviceName = "test",
            syncScheduleNotifier = noOpNotifier,
            codec = SyncCodec(TestItem.serializer()),
        )

        // Step 1: Simulate a CREATE that timed out (serverAttemptMade = true).
        localStore.insertLocalData(
            data = createData,
            httpRequest = makeRequest(
                body = buildJsonObject {
                    put("client_id", "c1")
                    put("name", "Original")
                },
            ),
            idempotencyKey = "idem-create-c1",
            requestTag = TestRequestTag.DEFAULT,
            serverAttemptMade = true,
        )

        // Step 2: Offline UPDATE on the same object.
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
                baseData = createData,
                hasPendingRequests = true,
            ),
            requestTag = TestRequestTag.UPDATE,
        )

        // Verify: 2 pending requests (CREATE with serverAttemptMade + UPDATE)
        val pendingBefore = localStore.pendingRequestQueueManager.getPendingRequests("c1")
        assertEquals(2, pendingBefore.size)
        assertTrue(pendingBefore[0].serverAttemptMade, "CREATE should have serverAttemptMade = true")

        // Step 3: Sync up.
        val driver = SyncDriver(
            serverManager = ServerManager(
                serviceBaseHeaders = emptyList(),
                httpClient = HttpClient(mockEngine),
            ),
            connectivityChecker = offlineChecker,
            codec = SyncCodec(TestItem.serializer()),
            serverProcessingConfig = testServerConfig(),
            localStoreManager = localStore,
            serviceName = "test",
            autoStart = false,
        )

        val synced = syncUpViaCoordinator(listOf(driver), db)

        assertEquals(2, synced, "Both CREATE and UPDATE should sync")
        assertEquals(2, requestCount, "Two HTTP requests should have been made")

        val row = db.syncDataQueries.getData("test", "c1", "server-1").executeAsOne()
        assertEquals(SyncableObject.SyncStatus.SYNCED, row.sync_status)

        val remaining = localStore.pendingRequestQueueManager.getPendingRequests("c1")
        assertEquals(0, remaining.size, "No pending requests should remain")

        driver.close()
    }

    // -----------------------------------------------------------------------
    // 2. Circular cross-service dependencies
    // -----------------------------------------------------------------------

    /**
     * Scenario: Service A creates an object referencing Service B via a
     * cross-service placeholder, and Service B creates an object referencing
     * Service A. Both are pending offline.
     *
     * Expected: Neither can resolve its dependency, so both are skipped.
     * No HTTP requests are made and pending count remains 2.
     */
    @Test
    fun `circular cross-service dependencies are skipped without deadlock`() = runBlocking {
        val db = TestDatabaseFactory.createInMemory()
        val requestLog = mutableListOf<String>()

        val mockEngine = MockEngine {
            requestLog.add("request")
            respond(
                content = wrapResponse(testItem(clientId = "a1", serverId = "server-a1")),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val (driverA, storeA) = createDriver("service-a", db, mockEngine)
        val (driverB, storeB) = createDriver("service-b", db, mockEngine)

        // Service A references Service B's object.
        storeA.insertLocalData(
            data = testItem(clientId = "a1", name = "A references B"),
            httpRequest = HttpRequest(
                method = HttpRequest.HttpMethod.POST,
                endpointUrl = "https://api.test.com/service-a",
                requestBody = buildJsonObject {
                    put("client_id", "a1")
                    put("b_ref", HttpRequest.crossServiceServerIdPlaceholder("service-b", "b1"))
                },
            ),
            idempotencyKey = "idem-a1",
            requestTag = TestRequestTag.DEFAULT,
        )

        // Service B references Service A's object.
        storeB.insertLocalData(
            data = testItem(clientId = "b1", name = "B references A"),
            httpRequest = HttpRequest(
                method = HttpRequest.HttpMethod.POST,
                endpointUrl = "https://api.test.com/service-b",
                requestBody = buildJsonObject {
                    put("client_id", "b1")
                    put("a_ref", HttpRequest.crossServiceServerIdPlaceholder("service-a", "a1"))
                },
            ),
            idempotencyKey = "idem-b1",
            requestTag = TestRequestTag.DEFAULT,
        )

        val synced = SyncUpCoordinator(listOf(driverA, driverB), db).syncUpAll()

        assertEquals(0, synced, "Neither should sync due to circular dependency")
        assertEquals(0, requestLog.size, "No HTTP requests should have been made")

        // Both requests should still be pending.
        assertEquals(1, storeA.pendingRequestQueueManager.getPendingRequests("a1").size)
        assertEquals(1, storeB.pendingRequestQueueManager.getPendingRequests("b1").size)

        driverA.close()
        driverB.close()
    }

    // -----------------------------------------------------------------------
    // 3. Unresolvable cross-service dependency (dependency removed from queue)
    // -----------------------------------------------------------------------

    /**
     * Scenario: Service A queues a request referencing Service B's object.
     * Service B's CREATE syncs but returns RemovePendingRequest (API rejected it).
     * Service A's request remains blocked because the dependency has no serverId.
     *
     * Expected: Service A's request is skipped. It stays in the queue since
     * the dependency never received a serverId.
     */
    @Test
    fun `cross-service dependency blocked when dependency sync fails`() = runBlocking {
        val db = TestDatabaseFactory.createInMemory()
        val requestLog = mutableListOf<Pair<String, String>>()

        // Service B's response triggers RemovePendingRequest (no "data" key).
        val serviceB_responses = ArrayDeque(listOf(
            buildJsonObject { put("error", "rejected") }.toString()
        ))
        // Service A should never be called.
        val serviceA_responses = ArrayDeque<String>()

        val serviceBEngine = MockEngine { request ->
            val bodyText = (request.body as? TextContent)?.text ?: ""
            requestLog.add("service-b" to bodyText)
            respond(
                content = serviceB_responses.removeFirst(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val serviceAEngine = MockEngine { request ->
            val bodyText = (request.body as? TextContent)?.text ?: ""
            requestLog.add("service-a" to bodyText)
            respond(
                content = serviceA_responses.removeFirst(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val (driverB, storeB) = createDriver("service-b", db, serviceBEngine)
        val (driverA, storeA) = createDriver("service-a", db, serviceAEngine)

        // Service B: queue a CREATE.
        storeB.insertLocalData(
            data = testItem(clientId = "b1", name = "B item"),
            httpRequest = makeRequest(
                endpoint = "https://api.test.com/service-b",
                body = buildJsonObject { put("client_id", "b1") },
            ),
            idempotencyKey = "idem-b1",
            requestTag = TestRequestTag.DEFAULT,
        )

        // Service A: queue a CREATE referencing service-b's object.
        storeA.insertLocalData(
            data = testItem(clientId = "a1", name = "A depends on B"),
            httpRequest = HttpRequest(
                method = HttpRequest.HttpMethod.POST,
                endpointUrl = "https://api.test.com/service-a",
                requestBody = buildJsonObject {
                    put("client_id", "a1")
                    put("b_ref", HttpRequest.crossServiceServerIdPlaceholder("service-b", "b1"))
                },
            ),
            idempotencyKey = "idem-a1",
            requestTag = TestRequestTag.DEFAULT,
        )

        SyncUpCoordinator(listOf(driverB, driverA), db).syncUpAll()

        // Service B's request was sent but returned no "data" → RemovePendingRequest.
        // Service A's request couldn't resolve because B never got a serverId.
        assertEquals(1, requestLog.size, "Only service B's request should have been sent")
        assertEquals("service-b", requestLog[0].first)

        // Service B's pending request was removed (API rejected it).
        assertEquals(0, storeB.pendingRequestQueueManager.getPendingRequests("b1").size,
            "Service B's request should be removed after RemovePendingRequest")

        // Service A's request is still pending — dependency unresolved.
        assertEquals(1, storeA.pendingRequestQueueManager.getPendingRequests("a1").size,
            "Service A's request should remain pending")

        driverA.close()
        driverB.close()
    }

    // -----------------------------------------------------------------------
    // 4. Sync-down delivers conflicting data while pending UPDATE exists
    // -----------------------------------------------------------------------

    /**
     * Scenario:
     *   1. Create an item locally (offline) — pending CREATE with name = "Local".
     *   2. Sync-down delivers server data for the same clientId but name = "Server".
     *   3. The rebase detects that the local change and server change conflict on `name`.
     *
     * Expected: The item is marked with a conflict status and the pending
     * request is preserved with conflict info.
     */
    @Test
    fun `sync-down with conflicting pending CREATE marks item as CONFLICT`() = runBlocking {
        val db = TestDatabaseFactory.createInMemory()
        val status = com.les.buoyient.globalconfigs.BuoyientStatus(
            db, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))

        val serverItem = testItem(clientId = "c1", serverId = "s1", name = "Server", value = 1)

        val mockEngine = MockEngine {
            respond(
                content = wrapListResponse(listOf(serverItem)),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val onlineChecker = object : ConnectivityChecker {
            override fun isOnline(): Boolean = true
        }
        val (driver, localStore) = createDriver("test", db, mockEngine,
            serverConfig = testServerConfigWithSyncDown(),
            connectivityChecker = onlineChecker)

        // Step 1: Create an item locally with a different name.
        localStore.insertLocalData(
            data = testItem(clientId = "c1", name = "Local", value = 1),
            httpRequest = makeRequest(),
            idempotencyKey = "idem-c1",
            requestTag = TestRequestTag.DEFAULT,
        )

        // Step 2: Sync down — server has a different name for the same clientId.
        driver.syncDownFromServer()

        // Step 3: Verify conflict is detected.
        val pending = localStore.pendingRequestQueueManager.getPendingRequests("c1")
        assertEquals(1, pending.size, "Pending request should be preserved during conflict")

        assertTrue(status.hasPendingConflicts.value,
            "Should report pending conflicts after sync-down with conflicting data")

        driver.close()
    }

    // -----------------------------------------------------------------------
    // 5. Squash + Encryption combined
    // -----------------------------------------------------------------------

    /**
     * Scenario: Enable encryption AND squash queue strategy. Queue a CREATE
     * then an UPDATE. Verify that the squash merge works correctly despite
     * the data being encrypted at rest.
     *
     * Expected: After squash, there's 1 pending request with encrypted
     * data in the DB, and the PendingRequestQueueManager can decrypt and
     * read it correctly.
     */
    @Test
    fun `squash strategy with encryption produces correct merged request`() = runBlocking {
        val db = TestDatabaseFactory.createInMemory()
        val codec = SyncCodec(TestItem.serializer())
        val encryptionProvider = TestEncryptionProvider()

        val squashMerger = SquashRequestMerger { createRequest, updateRequest ->
            HttpRequest(createRequest.method, createRequest.endpointUrl, updateRequest.requestBody)
        }

        val localStore = LocalStoreManager<TestItem, TestRequestTag>(
            database = db,
            serviceName = "enc-squash",
            syncScheduleNotifier = noOpNotifier,
            codec = codec,
            encryptionProvider = encryptionProvider,
            queueStrategy = PendingRequestQueueStrategy.Squash(
                squashUpdateIntoCreate = squashMerger,
            ),
        )

        val item = testItem(clientId = "c1", name = "Original", value = 10)

        // Step 1: Offline CREATE.
        localStore.insertLocalData(
            data = item,
            httpRequest = HttpRequest(
                method = HttpRequest.HttpMethod.POST,
                endpointUrl = "https://api.test.com/items",
                requestBody = buildJsonObject {
                    put("client_id", "c1")
                    put("name", "Original")
                    put("value", 10)
                },
            ),
            idempotencyKey = "idem-create",
            requestTag = TestRequestTag.DEFAULT,
        )

        // Step 2: Offline UPDATE — should squash into CREATE.
        val updatedItem = item.copy(name = "Updated", value = 42, version = "2")
        localStore.updateLocalData(
            data = updatedItem,
            idempotencyKey = "idem-update",
            updateRequest = HttpRequest(
                method = HttpRequest.HttpMethod.PUT,
                endpointUrl = "https://api.test.com/items/{serverId}",
                requestBody = buildJsonObject {
                    put("client_id", "c1")
                    put("name", "Updated")
                    put("value", 42)
                },
            ),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Squash(
                baseData = item,
                hasPendingRequests = true,
                squashUpdateIntoCreate = squashMerger,
            ),
            requestTag = TestRequestTag.UPDATE,
        )

        // Verify: only 1 pending request after squash.
        val pending = localStore.pendingRequestQueueManager.getPendingRequests("c1")
        assertEquals(1, pending.size, "Squash should merge into 1 request")
        assertEquals(PendingSyncRequest.Type.CREATE, pending.first().type)

        // Verify the merged request body contains the updated data.
        val body = pending.first().request.requestBody.toString()
        assertTrue(body.contains("Updated"), "Merged request should contain updated name")
        assertTrue(body.contains("42"), "Merged request should contain updated value")

        // Verify the data in the local store is readable (decrypted correctly).
        val entry = localStore.getData(clientId = "c1", serverId = null)
        assertNotNull(entry)
        assertEquals("Updated", entry.data.name)
        assertEquals(42, entry.data.value)

        // Verify raw DB is encrypted.
        val rawRow = db.syncDataQueries.getData("enc-squash", "c1", null).executeAsOne()
        assertTrue(rawRow.data_blob.startsWith("ENC:"), "data_blob should be encrypted")
        assertFalse(rawRow.data_blob.contains("Updated"), "Encrypted data should not contain plaintext")
    }

    // -----------------------------------------------------------------------
    // 6. Squash after server-attempted timeout — subsequent UPDATE preserved
    // -----------------------------------------------------------------------

    /**
     * Scenario: Using squash strategy, a CREATE has serverAttemptMade = true
     * (timed out). Then a new UPDATE arrives offline.
     *
     * Expected: The UPDATE is stored separately (not squashed into the
     * server-attempted CREATE), preserving both entries so the CREATE
     * can be retried safely.
     */
    @Test
    fun `squash does not merge into server-attempted CREATE and both sync correctly`() = runBlocking {
        val db = TestDatabaseFactory.createInMemory()
        val codec = SyncCodec(TestItem.serializer())

        val squashMerger = SquashRequestMerger { createRequest, updateRequest ->
            HttpRequest(createRequest.method, createRequest.endpointUrl, updateRequest.requestBody)
        }

        val squashQueueManager = PendingRequestQueueManager<TestItem, TestRequestTag>(
            database = db,
            serviceName = "squash-timeout",
            strategy = PendingRequestQueueStrategy.Squash(squashUpdateIntoCreate = squashMerger),
            codec = codec,
        )

        val item = testItem(clientId = "c1", name = "Original")

        // Insert sync_data row.
        db.syncDataQueries.insertLocalData(
            service_name = "squash-timeout",
            client_id = "c1",
            server_id = null,
            version = "1",
            data_blob = json.encodeToString(TestItem.serializer(), item),
            sync_status = SyncableObject.SyncStatus.PENDING_CREATE,
        )

        // Queue CREATE with serverAttemptMade = true (timed out).
        squashQueueManager.queueCreateRequest(
            data = item,
            httpRequest = makeRequest(
                body = buildJsonObject { put("name", "Original") },
            ),
            idempotencyKey = "idem-create",
            serverAttemptMade = true,
            requestTag = TestRequestTag.DEFAULT,
        )

        // Queue UPDATE — should NOT squash into the server-attempted CREATE.
        val updatedItem = item.copy(name = "After Timeout")
        squashQueueManager.queueUpdateRequest(
            data = updatedItem,
            idempotencyKey = "idem-update",
            updateRequest = HttpRequest(
                HttpRequest.HttpMethod.PUT,
                "https://api.test.com/items/{serverId}",
                buildJsonObject { put("name", "After Timeout") },
            ),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Queue.ForcedAfterServerAttempt(
                baseData = item,
                hasPendingRequests = true,
            ),
            requestTag = TestRequestTag.UPDATE,
        )

        // Should be 2 separate entries.
        val pending = squashQueueManager.getPendingRequests("c1")
        assertEquals(2, pending.size, "Should not squash into server-attempted CREATE")
        assertEquals(PendingSyncRequest.Type.CREATE, pending[0].type)
        assertEquals(PendingSyncRequest.Type.UPDATE, pending[1].type)
        assertTrue(pending[0].serverAttemptMade,
            "CREATE should retain serverAttemptMade = true")
        assertFalse(pending[1].serverAttemptMade,
            "UPDATE should have serverAttemptMade = false")
    }

    // -----------------------------------------------------------------------
    // 7. Offline create then void cancels both (local-only item)
    // -----------------------------------------------------------------------

    /**
     * Scenario:
     *   1. Create an object offline (pending CREATE queued).
     *   2. Void it before sync.
     *
     * Expected: The item is voided locally. The CREATE pending request
     * is either removed or a VOID replaces it, so no server request is
     * needed for a never-synced item.
     */
    @Test
    fun `void after offline create removes pending create for local-only item`() = runBlocking {
        val db = TestDatabaseFactory.createInMemory()
        val localStore = LocalStoreManager<TestItem, TestRequestTag>(
            database = db,
            serviceName = "test",
            syncScheduleNotifier = noOpNotifier,
            codec = SyncCodec(TestItem.serializer()),
        )

        val item = testItem(clientId = "c1", name = "Ephemeral")

        // Step 1: Offline CREATE.
        localStore.insertLocalData(
            data = item,
            httpRequest = makeRequest(
                body = buildJsonObject { put("name", "Ephemeral") },
            ),
            idempotencyKey = "idem-create-c1",
            requestTag = TestRequestTag.DEFAULT,
        )

        assertEquals(1, localStore.pendingRequestQueueManager.getPendingRequests("c1").size,
            "Should have 1 pending CREATE")

        // Step 2: Void the local-only item.
        val localItem = localStore.getData(clientId = "c1", serverId = null)!!.data
        localStore.voidLocalOnlyData(localItem)

        // Verify: the pending requests for this item should not include
        // a CREATE that would be sent to the server for a never-synced item.
        val pending = localStore.pendingRequestQueueManager.getPendingRequests("c1")
        // For a local-only item, voiding should clean up the CREATE.
        // The void itself may or may not be queued (depends on whether
        // the item has a serverId), but the CREATE should be gone.
        val createRequests = pending.filter { it.type == PendingSyncRequest.Type.CREATE }
        val voidRequests = pending.filter { it.type == PendingSyncRequest.Type.VOID }
        assertTrue(createRequests.isEmpty() || voidRequests.isNotEmpty(),
            "CREATE should be removed or accompanied by a VOID, got: ${pending.map { it.type }}")
    }

    // -----------------------------------------------------------------------
    // 8. Cross-service placeholder in both URL and body resolves both
    // -----------------------------------------------------------------------

    /**
     * Scenario: An offline request has a cross-service placeholder in both
     * the endpoint URL path and the JSON request body. The dependency syncs
     * first.
     *
     * Expected: Both the URL and body placeholders are resolved before the
     * request is sent.
     */
    @Test
    fun `cross-service placeholder resolved in both URL and body`() = runBlocking {
        val db = TestDatabaseFactory.createInMemory()
        val requestLog = mutableListOf<Pair<String, String>>()

        val orderServerId = "server-order-42"
        val orderResponses = ArrayDeque(listOf(
            wrapResponse(testItem(clientId = "order-1", serverId = orderServerId, name = "Order")),
        ))
        val paymentResponses = ArrayDeque(listOf(
            wrapResponse(testItem(clientId = "payment-1", serverId = "server-payment-99", name = "Payment")),
        ))

        fun buildEngine(name: String, responses: ArrayDeque<String>) = MockEngine { request ->
            val bodyText = (request.body as? TextContent)?.text ?: ""
            requestLog.add(name to "${request.url}|$bodyText")
            respond(
                content = responses.removeFirst(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val (orderDriver, orderStore) = createDriver(
            "orders", db, buildEngine("orders", orderResponses))
        val (paymentDriver, paymentStore) = createDriver(
            "payments", db, buildEngine("payments", paymentResponses))

        // Queue order CREATE.
        orderStore.insertLocalData(
            data = testItem(clientId = "order-1", name = "Order"),
            httpRequest = HttpRequest(
                method = HttpRequest.HttpMethod.POST,
                endpointUrl = "https://api.test.com/orders",
                requestBody = buildJsonObject {
                    put("client_id", "order-1")
                    put("name", "Order")
                },
            ),
            idempotencyKey = "idem-order-1",
            requestTag = TestRequestTag.DEFAULT,
        )

        // Queue payment CREATE with placeholder in BOTH URL and body.
        paymentStore.insertLocalData(
            data = testItem(clientId = "payment-1", name = "Payment"),
            httpRequest = HttpRequest(
                method = HttpRequest.HttpMethod.POST,
                endpointUrl = "https://api.test.com/orders/${
                    HttpRequest.crossServiceServerIdPlaceholder("orders", "order-1")
                }/payments",
                requestBody = buildJsonObject {
                    put("client_id", "payment-1")
                    put("order_id", HttpRequest.crossServiceServerIdPlaceholder("orders", "order-1"))
                    put("amount", 5000)
                },
            ),
            idempotencyKey = "idem-payment-1",
            requestTag = TestRequestTag.DEFAULT,
        )

        val synced = SyncUpCoordinator(listOf(orderDriver, paymentDriver), db).syncUpAll()

        assertEquals(2, synced, "Both requests should sync")
        assertEquals(2, requestLog.size)
        assertEquals("orders", requestLog[0].first)
        assertEquals("payments", requestLog[1].first)

        val paymentRequest = requestLog[1].second
        // URL should contain the resolved serverId.
        assertTrue(paymentRequest.contains(orderServerId),
            "Payment request should contain resolved order server ID, got: $paymentRequest")
        // No unresolved placeholders should remain.
        assertFalse(paymentRequest.contains("{cross:"),
            "No unresolved placeholders should remain, got: $paymentRequest")

        orderDriver.close()
        paymentDriver.close()
    }

    // -----------------------------------------------------------------------
    // 9. Multiple services — partial sync failure leaves others intact
    // -----------------------------------------------------------------------

    /**
     * Scenario: Two independent services sync up. Service A succeeds.
     * Service B's server returns an error. Service A's data should be
     * fully synced regardless.
     */
    @Test
    fun `partial sync failure in one service does not affect another`() = runBlocking {
        val db = TestDatabaseFactory.createInMemory()

        val serviceAResponses = ArrayDeque(listOf(
            wrapResponse(testItem(clientId = "a1", serverId = "server-a1", name = "A Item")),
        ))
        val serviceBResponses = ArrayDeque(listOf(
            // Service B returns a 500 error.
            buildJsonObject { put("error", "Internal Server Error") }.toString(),
        ))

        val engineA = MockEngine {
            respond(
                content = serviceAResponses.removeFirst(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val engineB = MockEngine {
            respond(
                content = serviceBResponses.removeFirst(),
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val (driverA, storeA) = createDriver("service-a", db, engineA)
        val (driverB, storeB) = createDriver("service-b", db, engineB)

        storeA.insertLocalData(
            data = testItem(clientId = "a1", name = "A Item"),
            httpRequest = makeRequest(endpoint = "https://api.test.com/a"),
            idempotencyKey = "idem-a1",
            requestTag = TestRequestTag.DEFAULT,
        )

        storeB.insertLocalData(
            data = testItem(clientId = "b1", name = "B Item"),
            httpRequest = makeRequest(endpoint = "https://api.test.com/b"),
            idempotencyKey = "idem-b1",
            requestTag = TestRequestTag.DEFAULT,
        )

        SyncUpCoordinator(listOf(driverA, driverB), db).syncUpAll()

        // Service A should have synced successfully.
        val rowA = db.syncDataQueries.getData("service-a", "a1", "server-a1").executeAsOne()
        assertEquals(SyncableObject.SyncStatus.SYNCED, rowA.sync_status,
            "Service A should be fully synced")
        assertEquals(0, storeA.pendingRequestQueueManager.getPendingRequests("a1").size)

        // Service B should still have its pending request.
        val pendingB = storeB.pendingRequestQueueManager.getPendingRequests("b1")
        assertEquals(1, pendingB.size, "Service B's request should remain pending after failure")

        driverA.close()
        driverB.close()
    }

    // -----------------------------------------------------------------------
    // 10. Offline CREATE + UPDATE + UPDATE — all three sync in order
    // -----------------------------------------------------------------------

    /**
     * Scenario: Three queued operations for the same item: CREATE, UPDATE 1,
     * UPDATE 2. All sync in a single pass with placeholder resolution.
     *
     * Expected: All three HTTP requests are sent in order. The final state
     * is SYNCED with the latest data.
     */
    @Test
    fun `three queued operations sync in correct order with placeholder resolution`() = runBlocking {
        val db = TestDatabaseFactory.createInMemory()

        val item = testItem(clientId = "c1", name = "V1", value = 1)
        val update1 = item.copy(name = "V2", value = 2, version = "2")
        val update2 = item.copy(name = "V3", value = 3, version = "3")

        val responses = ArrayDeque(listOf(
            wrapResponse(item.copy(serverId = "s1")),
            wrapResponse(update1.copy(serverId = "s1")),
            wrapResponse(update2.copy(serverId = "s1")),
        ))

        val capturedUrls = mutableListOf<String>()
        val mockEngine = MockEngine { request ->
            capturedUrls.add(request.url.toString())
            respond(
                content = responses.removeFirst(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val localStore = LocalStoreManager<TestItem, TestRequestTag>(
            database = db,
            serviceName = "test",
            syncScheduleNotifier = noOpNotifier,
            codec = SyncCodec(TestItem.serializer()),
        )

        val updateEndpoint = "https://api.test.com/items/${HttpRequest.SERVER_ID_PLACEHOLDER}"

        // CREATE
        localStore.insertLocalData(
            data = item,
            httpRequest = makeRequest(body = buildJsonObject { put("name", "V1") }),
            idempotencyKey = "idem-create",
            requestTag = TestRequestTag.DEFAULT,
        )

        // UPDATE 1
        localStore.updateLocalData(
            data = update1,
            idempotencyKey = "idem-update1",
            updateRequest = makeRequest(
                method = HttpRequest.HttpMethod.PUT,
                endpoint = updateEndpoint,
                body = buildJsonObject { put("name", "V2") },
            ),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Queue.Preferred(
                baseData = item,
                hasPendingRequests = true,
            ),
            requestTag = TestRequestTag.UPDATE,
        )

        // UPDATE 2
        localStore.updateLocalData(
            data = update2,
            idempotencyKey = "idem-update2",
            updateRequest = makeRequest(
                method = HttpRequest.HttpMethod.PUT,
                endpoint = updateEndpoint,
                body = buildJsonObject { put("name", "V3") },
            ),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Queue.Preferred(
                baseData = update1,
                hasPendingRequests = true,
            ),
            requestTag = TestRequestTag.UPDATE,
        )

        val driver = SyncDriver(
            serverManager = ServerManager(
                serviceBaseHeaders = emptyList(),
                httpClient = HttpClient(mockEngine),
            ),
            connectivityChecker = offlineChecker,
            codec = SyncCodec(TestItem.serializer()),
            serverProcessingConfig = testServerConfig(),
            localStoreManager = localStore,
            serviceName = "test",
            autoStart = false,
        )

        val synced = syncUpViaCoordinator(listOf(driver), db)

        assertEquals(3, synced, "All three operations should sync")
        assertEquals(3, capturedUrls.size, "Three HTTP requests should be made")

        // The UPDATE URLs should contain the resolved server ID.
        assertTrue(capturedUrls[1].contains("s1"),
            "UPDATE 1 URL should contain server ID, got: ${capturedUrls[1]}")
        assertTrue(capturedUrls[2].contains("s1"),
            "UPDATE 2 URL should contain server ID, got: ${capturedUrls[2]}")

        val row = db.syncDataQueries.getData("test", "c1", "s1").executeAsOne()
        assertEquals(SyncableObject.SyncStatus.SYNCED, row.sync_status)

        assertEquals(0, localStore.pendingRequestQueueManager.getPendingRequests("c1").size)

        driver.close()
    }
}
