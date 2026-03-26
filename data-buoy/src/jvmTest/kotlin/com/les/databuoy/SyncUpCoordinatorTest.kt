package com.les.databuoy

import com.les.databuoy.db.SyncDatabase
import com.les.databuoy.globalconfigs.DataBuoyStatus
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [com.les.databuoy.sync.SyncUpCoordinator] — verifies that pending requests across
 * multiple services are uploaded in global insertion order rather than
 * per-service order.
 */
class SyncUpCoordinatorTest {

    // region helpers

    private enum class TestRequestTag(override val value: String) : ServiceRequestTag {
        DEFAULT("default"),
    }

    private val noOpNotifier: SyncScheduleNotifier = NoOpSyncScheduleNotifier

    private val offlineChecker = object : ConnectivityChecker {
        override fun isOnline(): Boolean = false
    }

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

    private val json = Json { ignoreUnknownKeys = true }

    private fun wrapResponse(item: TestItem): String = buildJsonObject {
        put("data", json.encodeToJsonElement(TestItem.serializer(), item))
    }.toString()

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
     * Creates a [com.les.databuoy.sync.SyncDriver] for a given service name, backed by the shared
     * [database] and recording requests to [requestLog].
     *
     * @param responseQueue queue of pre-built response bodies; one is consumed per request.
     */
    private fun createDriver(
        serviceName: String,
        database: SyncDatabase,
        requestLog: MutableList<String>,
        responseQueue: ArrayDeque<String>,
        status: DataBuoyStatus? = null,
    ): Pair<SyncDriver<TestItem, TestRequestTag>, LocalStoreManager<TestItem, TestRequestTag>> {
        val localStore = LocalStoreManager<TestItem, TestRequestTag>(
            database = database,
            serviceName = serviceName,
            syncScheduleNotifier = noOpNotifier,
            codec = SyncCodec(TestItem.serializer()),
            status = status ?: DataBuoyStatus(database),
        )

        // Mock engine that logs which service handled the request.
        val mockEngine = MockEngine {
            requestLog.add(serviceName)
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

        val driver = SyncDriver(
            serverManager = serverManager,
            connectivityChecker = offlineChecker,
            codec = SyncCodec(TestItem.serializer()),
            serverProcessingConfig = testServerConfig(),
            localStoreManager = localStore,
            serviceName = serviceName,
            autoStart = false,
        )

        return driver to localStore
    }

    // endregion

    /**
     * Verifies that [com.les.databuoy.sync.SyncUpCoordinator.syncUpAll] dispatches pending requests
     * in global insertion order across multiple services.
     *
     * Setup:
     *   1. CREATE in service "alpha" (pending_request_id = 1)
     *   2. CREATE in service "beta"  (pending_request_id = 2)
     *   3. CREATE in service "alpha" (pending_request_id = 3)
     *
     * Expected upload order: alpha, beta, alpha — matching insertion order,
     * NOT alpha, alpha, beta (which would happen with per-service iteration).
     */
    @Test
    fun `syncUpAll dispatches requests in global insertion order`() = runBlocking {
        val db = TestDatabaseFactory.createInMemory()
        val status = DataBuoyStatus(db)
        val requestLog = mutableListOf<String>()

        // Pre-build responses for each CREATE, keyed to the expected client_id.
        val alphaResponses = ArrayDeque(listOf(
            wrapResponse(testItem(clientId = "a1", serverId = "server_a1")),
            wrapResponse(testItem(clientId = "a2", serverId = "server_a2")),
        ))
        val betaResponses = ArrayDeque(listOf(
            wrapResponse(testItem(clientId = "b1", serverId = "server_b1")),
        ))

        val (alphaDriver, alphaStore) = createDriver("alpha", db, requestLog, alphaResponses, status)
        val (betaDriver, betaStore) = createDriver("beta", db, requestLog, betaResponses, status)

        // Queue requests in interleaved order across services.
        // 1. CREATE in alpha
        alphaStore.insertLocalData(
            data = testItem(clientId = "a1", name = "Alpha 1"),
            httpRequest = makeRequest(body = buildJsonObject {
                put("client_id", "a1")
                put("name", "Alpha 1")
            }),
            idempotencyKey = "idem-a1",
            requestTag = TestRequestTag.DEFAULT,
        )
        // 2. CREATE in beta
        betaStore.insertLocalData(
            data = testItem(clientId = "b1", name = "Beta 1"),
            httpRequest = makeRequest(body = buildJsonObject {
                put("client_id", "b1")
                put("name", "Beta 1")
            }),
            idempotencyKey = "idem-b1",
            requestTag = TestRequestTag.DEFAULT,
        )
        // 3. CREATE in alpha
        alphaStore.insertLocalData(
            data = testItem(clientId = "a2", name = "Alpha 2"),
            httpRequest = makeRequest(body = buildJsonObject {
                put("client_id", "a2")
                put("name", "Alpha 2")
            }),
            idempotencyKey = "idem-a2",
            requestTag = TestRequestTag.DEFAULT,
        )

        // Act — use the coordinator for globally-ordered sync.
        val coordinator = SyncUpCoordinator(
            drivers = listOf(alphaDriver, betaDriver),
            database = db,
            status = status,
        )
        val synced = coordinator.syncUpAll()

        // Assert
        assertEquals(3, synced, "All 3 pending requests should have been synced")
        assertEquals(
            listOf("alpha", "beta", "alpha"),
            requestLog,
            "Requests should be dispatched in global insertion order (alpha, beta, alpha), not per-service order (alpha, alpha, beta)",
        )
        assertEquals(0, status.pendingRequestCount.value)
        assertFalse(status.hasPendingConflicts.value)
    }

    /**
     * An empty participant list should produce zero synced requests and not throw.
     */
    @Test
    fun `syncUpAll with no participants returns zero`() = runBlocking {
        val db = TestDatabaseFactory.createInMemory()
        val status = DataBuoyStatus(db)
        val coordinator = SyncUpCoordinator(
            drivers = emptyList(),
            database = db,
            status = status,
        )
        val synced = coordinator.syncUpAll()
        assertEquals(0, synced)
        assertEquals(0, status.pendingRequestCount.value)
        assertFalse(status.hasPendingConflicts.value)
    }

    /**
     * A single participant with no pending requests should return zero.
     */
    @Test
    fun `syncUpAll with participant but no pending requests returns zero`() = runBlocking {
        val db = TestDatabaseFactory.createInMemory()
        val status = DataBuoyStatus(db)
        val requestLog = mutableListOf<String>()
        val (driver, _) = createDriver("alpha", db, requestLog, ArrayDeque(), status)

        val coordinator = SyncUpCoordinator(
            drivers = listOf(driver),
            database = db,
            status = status,
        )
        val synced = coordinator.syncUpAll()

        assertEquals(0, synced)
        assertEquals(0, requestLog.size, "No HTTP requests should have been made")
        assertEquals(0, status.pendingRequestCount.value)
        assertFalse(status.hasPendingConflicts.value)
    }

    /**
     * When a pending request belongs to a service that has no registered
     * participant, the coordinator should skip it and continue processing
     * other requests.
     */
    @Test
    fun `syncUpAll skips requests for unregistered services`() = runBlocking {
        val db = TestDatabaseFactory.createInMemory()
        val status = DataBuoyStatus(db)
        val requestLog = mutableListOf<String>()

        val alphaResponses = ArrayDeque(listOf(
            wrapResponse(testItem(clientId = "a1", serverId = "server_a1")),
        ))
        val (alphaDriver, alphaStore) = createDriver("alpha", db, requestLog, alphaResponses, status)

        // Also create a store for an unregistered service ("ghost") and queue a request.
        val ghostStore = LocalStoreManager<TestItem, TestRequestTag>(
            database = db,
            serviceName = "ghost",
            syncScheduleNotifier = noOpNotifier,
            codec = SyncCodec(TestItem.serializer()),
            status = status,
        )

        // 1. Queue in ghost (no participant will be registered for this)
        ghostStore.insertLocalData(
            data = testItem(clientId = "g1", name = "Ghost"),
            httpRequest = makeRequest(body = buildJsonObject { put("client_id", "g1") }),
            idempotencyKey = "idem-g1",
            requestTag = TestRequestTag.DEFAULT,
        )
        // 2. Queue in alpha
        alphaStore.insertLocalData(
            data = testItem(clientId = "a1", name = "Alpha"),
            httpRequest = makeRequest(body = buildJsonObject { put("client_id", "a1") }),
            idempotencyKey = "idem-a1",
            requestTag = TestRequestTag.DEFAULT,
        )

        // Only register alpha — ghost has no participant.
        val coordinator = SyncUpCoordinator(
            drivers = listOf(alphaDriver),
            database = db,
            status = status,
        )
        val synced = coordinator.syncUpAll()

        assertEquals(1, synced, "Only alpha's request should have been synced")
        assertEquals(listOf("alpha"), requestLog)
        assertEquals(1, status.pendingRequestCount.value, "Ghost's unregistered request remains pending")
        assertFalse(status.hasPendingConflicts.value)
    }

    /**
     * When an unresolved conflict exists, syncUpAll should block all uploads
     * and return zero — even for requests in other services.
     */
    @Test
    fun `syncUpAll blocks all uploads when unresolved conflict exists`() = runBlocking {
        val db = TestDatabaseFactory.createInMemory()
        val status = DataBuoyStatus(db)
        val requestLog = mutableListOf<String>()

        val alphaResponses = ArrayDeque(listOf(
            wrapResponse(testItem(clientId = "a1", serverId = "server_a1")),
        ))
        val (alphaDriver, alphaStore) = createDriver("alpha", db, requestLog, alphaResponses, status)

        // Queue a create in alpha.
        alphaStore.insertLocalData(
            data = testItem(clientId = "a1", name = "Alpha"),
            httpRequest = makeRequest(body = buildJsonObject { put("client_id", "a1") }),
            idempotencyKey = "idem-a1",
            requestTag = TestRequestTag.DEFAULT,
        )

        // Manually inject a conflict_info on the pending request to simulate an unresolved conflict.
        db.syncPendingEventsQueries.saveConflictInfo(
            conflict_info = """{"pending_request_id":1,"field_names":"name","base_data":{},"local_data":{},"server_data":{}}""",
            pending_request_id = 1,
        )

        val coordinator = SyncUpCoordinator(
            drivers = listOf(alphaDriver),
            database = db,
            status = status,
        )
        val synced = coordinator.syncUpAll()

        assertEquals(0, synced, "No requests should be synced while conflicts exist")
        assertEquals(0, requestLog.size, "No HTTP requests should have been made")
        assertEquals(1, status.pendingRequestCount.value)
        assertTrue(status.hasPendingConflicts.value)
    }
}
