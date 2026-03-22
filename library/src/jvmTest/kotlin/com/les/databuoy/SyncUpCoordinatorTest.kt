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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for [SyncUpCoordinator] — verifies that pending requests across
 * multiple services are uploaded in global insertion order rather than
 * per-service order.
 */
class SyncUpCoordinatorTest {

    // region helpers

    private enum class TestRequestTag(override val value: String) : ServiceRequestTag {
        DEFAULT("default"),
    }

    private val logger: SyncLogger = NoOpSyncLogger

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
            override fun fromResponseBody(requestTag: String, responseBody: JsonObject): TestItem? {
                val data = responseBody["data"]?.jsonObject ?: return null
                return Json.decodeFromJsonElement(TestItem.serializer(), data)
                    .withSyncStatus(SyncableObject.SyncStatus.Synced(""))
            }
        }
        override val globalHeaders: List<Pair<String, String>> = emptyList()
    }

    /**
     * Creates a [SyncDriver]-based [SyncUpParticipant] for a given service name,
     * backed by the shared [database] and recording requests to [requestLog].
     *
     * @param responseQueue queue of pre-built response bodies; one is consumed per request.
     */
    private fun createParticipant(
        serviceName: String,
        database: SyncDatabase,
        requestLog: MutableList<String>,
        responseQueue: ArrayDeque<String>,
    ): Pair<SyncUpParticipant, LocalStoreManager<TestItem, TestRequestTag>> {
        val localStore = LocalStoreManager<TestItem, TestRequestTag>(
            database = database,
            serviceName = serviceName,
            syncScheduleNotifier = noOpNotifier,
            codec = SyncCodec(TestItem.serializer()),
            logger = logger,
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
            logger = logger,
            httpClient = HttpClient(mockEngine),
        )

        val driver = object : SyncDriver<TestItem, TestRequestTag>(
            serverManager, offlineChecker, SyncCodec(TestItem.serializer()),
            testServerConfig(), localStore, logger, noOpNotifier,
        ) {}
        driver.stopPeriodicSyncDown()

        val participant = object : SyncUpParticipant {
            override val serviceName: String = serviceName
            override suspend fun syncUpSinglePendingRequest(pendingRequestId: Int): Boolean {
                return driver.syncUpSinglePendingRequest(pendingRequestId)
            }
        }

        return participant to localStore
    }

    // endregion

    /**
     * Verifies that [SyncUpCoordinator.syncUpAll] dispatches pending requests
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
        val requestLog = mutableListOf<String>()

        // Pre-build responses for each CREATE, keyed to the expected client_id.
        val alphaResponses = ArrayDeque(listOf(
            wrapResponse(testItem(clientId = "a1", serverId = "server_a1")),
            wrapResponse(testItem(clientId = "a2", serverId = "server_a2")),
        ))
        val betaResponses = ArrayDeque(listOf(
            wrapResponse(testItem(clientId = "b1", serverId = "server_b1")),
        ))

        val (alphaParticipant, alphaStore) = createParticipant("alpha", db, requestLog, alphaResponses)
        val (betaParticipant, betaStore) = createParticipant("beta", db, requestLog, betaResponses)

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
            participants = listOf(alphaParticipant, betaParticipant),
            database = db,
            logger = logger,
        )
        val synced = coordinator.syncUpAll()

        // Assert
        assertEquals(3, synced, "All 3 pending requests should have been synced")
        assertEquals(
            listOf("alpha", "beta", "alpha"),
            requestLog,
            "Requests should be dispatched in global insertion order (alpha, beta, alpha), not per-service order (alpha, alpha, beta)",
        )
    }
}
