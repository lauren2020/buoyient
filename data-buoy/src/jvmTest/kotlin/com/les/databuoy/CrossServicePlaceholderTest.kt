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
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for cross-service placeholder resolution: [com.les.databuoy.syncableobjectservicedatatypes.HttpRequest.crossServiceServerIdPlaceholder],
 * [com.les.databuoy.syncableobjectservicedatatypes.HttpRequest.containsCrossServicePlaceholders], [com.les.databuoy.syncableobjectservicedatatypes.HttpRequest.resolveCrossServicePlaceholders],
 * and end-to-end resolution through [com.les.databuoy.sync.SyncUpCoordinator].
 */
class CrossServicePlaceholderTest {

    // region Placeholder helper tests

    @Test
    fun `serverIdOrPlaceholder returns serverId when non-null`() {
        assertEquals("server-123", HttpRequest.serverIdOrPlaceholder("server-123"))
    }

    @Test
    fun `serverIdOrPlaceholder returns placeholder when null`() {
        assertEquals(HttpRequest.SERVER_ID_PLACEHOLDER, HttpRequest.serverIdOrPlaceholder(null))
    }

    @Test
    fun `versionOrPlaceholder returns version string when non-null`() {
        assertEquals("5", HttpRequest.versionOrPlaceholder("5"))
    }

    @Test
    fun `versionOrPlaceholder returns placeholder when null`() {
        assertEquals(HttpRequest.VERSION_PLACEHOLDER, HttpRequest.versionOrPlaceholder(null))
    }

    // endregion

    // region HttpRequest unit tests

    @Test
    fun `crossServiceServerIdPlaceholder creates correct placeholder string`() {
        val placeholder = HttpRequest.crossServiceServerIdPlaceholder("orders", "abc-123")
        assertEquals("{cross:orders:abc-123}", placeholder)
    }

    @Test
    fun `containsCrossServicePlaceholders returns true when placeholder in endpoint`() {
        val request = HttpRequest(
            method = HttpRequest.HttpMethod.POST,
            endpointUrl = "https://api.test.com/payments?order_id={cross:orders:abc-123}",
            requestBody = JsonObject(emptyMap()),
        )
        assertTrue(request.containsCrossServicePlaceholders())
    }

    @Test
    fun `containsCrossServicePlaceholders returns true when placeholder in body`() {
        val request = HttpRequest(
            method = HttpRequest.HttpMethod.POST,
            endpointUrl = "https://api.test.com/payments",
            requestBody = buildJsonObject {
                put("order_id", HttpRequest.crossServiceServerIdPlaceholder("orders", "abc-123"))
            },
        )
        assertTrue(request.containsCrossServicePlaceholders())
    }

    @Test
    fun `containsCrossServicePlaceholders returns false when no placeholders`() {
        val request = HttpRequest(
            method = HttpRequest.HttpMethod.POST,
            endpointUrl = "https://api.test.com/payments",
            requestBody = buildJsonObject { put("order_id", "server-order-1") },
        )
        assertFalse(request.containsCrossServicePlaceholders())
    }

    @Test
    fun `resolveCrossServicePlaceholders resolves placeholder in endpoint`() {
        val request = HttpRequest(
            method = HttpRequest.HttpMethod.POST,
            endpointUrl = "https://api.test.com/orders/{cross:orders:abc-123}/payments",
            requestBody = JsonObject(emptyMap()),
        )
        val resolved = request.resolveCrossServicePlaceholders { _, _ -> "server-order-1" }
        assertEquals(
            "https://api.test.com/orders/server-order-1/payments",
            resolved!!.endpointUrl,
        )
    }

    @Test
    fun `resolveCrossServicePlaceholders resolves placeholder in body`() {
        val request = HttpRequest(
            method = HttpRequest.HttpMethod.POST,
            endpointUrl = "https://api.test.com/payments",
            requestBody = buildJsonObject {
                put("order_id", HttpRequest.crossServiceServerIdPlaceholder("orders", "abc-123"))
                put("amount", 100)
            },
        )
        val resolved = request.resolveCrossServicePlaceholders { _, _ -> "server-order-1" }
        assertEquals("server-order-1", resolved!!.requestBody["order_id"]!!.toString().trim('"'))
    }

    @Test
    fun `resolveCrossServicePlaceholders returns null when dependency unresolved`() {
        val request = HttpRequest(
            method = HttpRequest.HttpMethod.POST,
            endpointUrl = "https://api.test.com/payments",
            requestBody = buildJsonObject {
                put("order_id", HttpRequest.crossServiceServerIdPlaceholder("orders", "abc-123"))
            },
        )
        val resolved = request.resolveCrossServicePlaceholders { _, _ -> null }
        assertNull(resolved)
    }

    @Test
    fun `resolveCrossServicePlaceholders resolves multiple different placeholders`() {
        val request = HttpRequest(
            method = HttpRequest.HttpMethod.POST,
            endpointUrl = "https://api.test.com/line-items",
            requestBody = buildJsonObject {
                put("order_id", HttpRequest.crossServiceServerIdPlaceholder("orders", "order-1"))
                put("product_id", HttpRequest.crossServiceServerIdPlaceholder("products", "prod-1"))
            },
        )
        val resolved = request.resolveCrossServicePlaceholders { serviceName, _ ->
            when (serviceName) {
                "orders" -> "server-order-1"
                "products" -> "server-prod-1"
                else -> null
            }
        }
        assertEquals("server-order-1", resolved!!.requestBody["order_id"]!!.toString().trim('"'))
        assertEquals("server-prod-1", resolved.requestBody["product_id"]!!.toString().trim('"'))
    }

    @Test
    fun `resolveCrossServicePlaceholders returns null if any dependency unresolved`() {
        val request = HttpRequest(
            method = HttpRequest.HttpMethod.POST,
            endpointUrl = "https://api.test.com/line-items",
            requestBody = buildJsonObject {
                put("order_id", HttpRequest.crossServiceServerIdPlaceholder("orders", "order-1"))
                put("product_id", HttpRequest.crossServiceServerIdPlaceholder("products", "prod-1"))
            },
        )
        // orders resolved, products not
        val resolved = request.resolveCrossServicePlaceholders { serviceName, _ ->
            when (serviceName) {
                "orders" -> "server-order-1"
                else -> null
            }
        }
        assertNull(resolved)
    }

    @Test
    fun `resolveCrossServicePlaceholders returns null when no placeholders present`() {
        val request = HttpRequest(
            method = HttpRequest.HttpMethod.POST,
            endpointUrl = "https://api.test.com/payments",
            requestBody = buildJsonObject { put("amount", 100) },
        )
        val resolved = request.resolveCrossServicePlaceholders { _, _ -> "any" }
        assertNull(resolved)
    }

    // endregion

    // region resolveAllPlaceholders tests

    @Test
    fun `resolveAllPlaceholders with no placeholders returns Resolved with unchanged request`() {
        val request = HttpRequest(
            method = HttpRequest.HttpMethod.POST,
            endpointUrl = "https://api.test.com/items",
            requestBody = buildJsonObject { put("name", "Test") },
        )
        val result = request.resolveAllPlaceholders(serverId = "s1", version = "1")
        assertIs<HttpRequest.PlaceholderResolutionResult.Resolved>(result)
        assertEquals(request.endpointUrl, result.request.endpointUrl)
        assertEquals(request.requestBody, result.request.requestBody)
    }

    @Test
    fun `resolveAllPlaceholders resolves serverId in URL`() {
        val request = HttpRequest(
            method = HttpRequest.HttpMethod.PATCH,
            endpointUrl = "https://api.test.com/items/${HttpRequest.SERVER_ID_PLACEHOLDER}",
            requestBody = buildJsonObject { put("name", "Test") },
        )
        val result = request.resolveAllPlaceholders(serverId = "server-42")
        assertIs<HttpRequest.PlaceholderResolutionResult.Resolved>(result)
        assertEquals("https://api.test.com/items/server-42", result.request.endpointUrl)
    }

    @Test
    fun `resolveAllPlaceholders returns UnresolvedServerId when serverId null and placeholder in URL`() {
        val request = HttpRequest(
            method = HttpRequest.HttpMethod.PATCH,
            endpointUrl = "https://api.test.com/items/${HttpRequest.SERVER_ID_PLACEHOLDER}",
            requestBody = buildJsonObject { put("name", "Test") },
        )
        val result = request.resolveAllPlaceholders(serverId = null)
        assertIs<HttpRequest.PlaceholderResolutionResult.UnresolvedServerId>(result)
    }

    @Test
    fun `resolveAllPlaceholders resolves serverId in body`() {
        val request = HttpRequest(
            method = HttpRequest.HttpMethod.PATCH,
            endpointUrl = "https://api.test.com/items",
            requestBody = buildJsonObject { put("id", HttpRequest.SERVER_ID_PLACEHOLDER) },
        )
        val result = request.resolveAllPlaceholders(serverId = "server-42")
        assertIs<HttpRequest.PlaceholderResolutionResult.Resolved>(result)
        assertEquals("server-42", result.request.requestBody["id"]!!.toString().trim('"'))
    }

    @Test
    fun `resolveAllPlaceholders returns UnresolvedServerId when serverId null and placeholder in body`() {
        val request = HttpRequest(
            method = HttpRequest.HttpMethod.PATCH,
            endpointUrl = "https://api.test.com/items",
            requestBody = buildJsonObject { put("id", HttpRequest.SERVER_ID_PLACEHOLDER) },
        )
        val result = request.resolveAllPlaceholders(serverId = null)
        assertIs<HttpRequest.PlaceholderResolutionResult.UnresolvedServerId>(result)
    }

    @Test
    fun `resolveAllPlaceholders resolves numeric version as JSON number`() {
        val request = HttpRequest(
            method = HttpRequest.HttpMethod.PATCH,
            endpointUrl = "https://api.test.com/items",
            requestBody = buildJsonObject { put("version", HttpRequest.VERSION_PLACEHOLDER) },
        )
        val result = request.resolveAllPlaceholders(version = "3")
        assertIs<HttpRequest.PlaceholderResolutionResult.Resolved>(result)
        val versionElement = result.request.requestBody["version"]!!.jsonPrimitive
        assertFalse(versionElement.isString, "Numeric version should resolve to a JSON number, not a string")
        assertEquals(3, versionElement.long)
    }

    @Test
    fun `resolveAllPlaceholders resolves non-numeric version as JSON string`() {
        val request = HttpRequest(
            method = HttpRequest.HttpMethod.PATCH,
            endpointUrl = "https://api.test.com/items",
            requestBody = buildJsonObject { put("version", HttpRequest.VERSION_PLACEHOLDER) },
        )
        val result = request.resolveAllPlaceholders(version = "etag-abc-123")
        assertIs<HttpRequest.PlaceholderResolutionResult.Resolved>(result)
        val versionElement = result.request.requestBody["version"]!!.jsonPrimitive
        assertTrue(versionElement.isString, "Non-numeric version should resolve to a JSON string")
        assertEquals("etag-abc-123", versionElement.content)
    }

    @Test
    fun `resolveAllPlaceholders leaves version placeholder when version is null`() {
        val request = HttpRequest(
            method = HttpRequest.HttpMethod.PATCH,
            endpointUrl = "https://api.test.com/items",
            requestBody = buildJsonObject { put("version", HttpRequest.VERSION_PLACEHOLDER) },
        )
        val result = request.resolveAllPlaceholders(version = null)
        assertIs<HttpRequest.PlaceholderResolutionResult.Resolved>(result)
        assertEquals(
            HttpRequest.VERSION_PLACEHOLDER,
            result.request.requestBody["version"]!!.toString().trim('"'),
        )
    }

    @Test
    fun `resolveAllPlaceholders resolves cross-service placeholders`() {
        val request = HttpRequest(
            method = HttpRequest.HttpMethod.POST,
            endpointUrl = "https://api.test.com/payments",
            requestBody = buildJsonObject {
                put("order_id", HttpRequest.crossServiceServerIdPlaceholder("orders", "order-1"))
            },
        )
        val result = request.resolveAllPlaceholders(
            crossServiceResolver = { _, _ -> "server-order-1" },
        )
        assertIs<HttpRequest.PlaceholderResolutionResult.Resolved>(result)
        assertEquals("server-order-1", result.request.requestBody["order_id"]!!.toString().trim('"'))
    }

    @Test
    fun `resolveAllPlaceholders returns UnresolvedCrossService when resolver returns null`() {
        val request = HttpRequest(
            method = HttpRequest.HttpMethod.POST,
            endpointUrl = "https://api.test.com/payments",
            requestBody = buildJsonObject {
                put("order_id", HttpRequest.crossServiceServerIdPlaceholder("orders", "order-1"))
            },
        )
        val result = request.resolveAllPlaceholders(
            crossServiceResolver = { _, _ -> null },
        )
        assertIs<HttpRequest.PlaceholderResolutionResult.UnresolvedCrossService>(result)
    }

    @Test
    fun `resolveAllPlaceholders returns UnresolvedCrossService when no resolver provided`() {
        val request = HttpRequest(
            method = HttpRequest.HttpMethod.POST,
            endpointUrl = "https://api.test.com/payments",
            requestBody = buildJsonObject {
                put("order_id", HttpRequest.crossServiceServerIdPlaceholder("orders", "order-1"))
            },
        )
        val result = request.resolveAllPlaceholders(crossServiceResolver = null)
        assertIs<HttpRequest.PlaceholderResolutionResult.UnresolvedCrossService>(result)
    }

    @Test
    fun `resolveAllPlaceholders resolves all placeholder types in one call`() {
        val request = HttpRequest(
            method = HttpRequest.HttpMethod.PATCH,
            endpointUrl = "https://api.test.com/items/${HttpRequest.SERVER_ID_PLACEHOLDER}",
            requestBody = buildJsonObject {
                put("version", HttpRequest.VERSION_PLACEHOLDER)
                put("order_id", HttpRequest.crossServiceServerIdPlaceholder("orders", "order-1"))
            },
        )
        val result = request.resolveAllPlaceholders(
            serverId = "server-42",
            version = "5",
            crossServiceResolver = { _, _ -> "server-order-1" },
        )
        assertIs<HttpRequest.PlaceholderResolutionResult.Resolved>(result)
        assertEquals("https://api.test.com/items/server-42", result.request.endpointUrl)
        assertEquals(5, result.request.requestBody["version"]!!.jsonPrimitive.long)
        assertEquals("server-order-1", result.request.requestBody["order_id"]!!.toString().trim('"'))
    }

    @Test
    fun `resolveAllPlaceholders does not call cross-service resolver when no cross-service placeholders`() {
        val request = HttpRequest(
            method = HttpRequest.HttpMethod.POST,
            endpointUrl = "https://api.test.com/items",
            requestBody = buildJsonObject { put("name", "Test") },
        )
        var resolverCalled = false
        val result = request.resolveAllPlaceholders(
            crossServiceResolver = { _, _ ->
                resolverCalled = true
                "should-not-be-called"
            },
        )
        assertIs<HttpRequest.PlaceholderResolutionResult.Resolved>(result)
        assertFalse(resolverCalled, "Cross-service resolver should not be called when no placeholders exist")
    }

    // endregion

    // region SyncUpCoordinator integration test

    private enum class TestRequestTag(override val value: String) : ServiceRequestTag {
        DEFAULT("default"),
    }

    private val noOpNotifier: SyncScheduleNotifier = NoOpSyncScheduleNotifier

    private val offlineChecker = object : ConnectivityChecker {
        override fun isOnline(): Boolean = false
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun testItem(
        clientId: String,
        serverId: String? = null,
        version: String? = "1",
        name: String = "Test",
        value: Int = 0,
        syncStatus: SyncableObject.SyncStatus = SyncableObject.SyncStatus.LocalOnly,
    ) = TestItem(serverId, clientId, version, syncStatus, name, value)

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

    private fun createDriver(
        serviceName: String,
        database: SyncDatabase,
        requestLog: MutableList<Pair<String, String>>,
        responseQueue: ArrayDeque<String>,
    ): Pair<SyncDriver<TestItem, TestRequestTag>, LocalStoreManager<TestItem, TestRequestTag>> {
        val localStore = LocalStoreManager<TestItem, TestRequestTag>(
            database = database,
            serviceName = serviceName,
            syncScheduleNotifier = noOpNotifier,
            codec = SyncCodec(TestItem.serializer()),
        )

        val mockEngine = MockEngine { request ->
            val bodyText = (request.body as? TextContent)?.text ?: ""
            requestLog.add(serviceName to bodyText)
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

    /**
     * End-to-end test: Service A (orders) creates an item offline, then Service B
     * (payments) creates an item whose request body contains a cross-service
     * placeholder referencing Service A's object. After syncUpAll, the payment's
     * request body should contain the order's server ID.
     */
    @Test
    fun `syncUpAll resolves cross-service placeholders across services`() = runBlocking {
        val db = TestDatabaseFactory.createInMemory()
        val requestLog = mutableListOf<Pair<String, String>>()

        val orderServerId = "server-order-42"
        val orderResponses = ArrayDeque(listOf(
            wrapResponse(testItem(clientId = "order-1", serverId = orderServerId, name = "Order")),
        ))
        val paymentResponses = ArrayDeque(listOf(
            wrapResponse(testItem(clientId = "payment-1", serverId = "server-payment-99", name = "Payment")),
        ))

        val (orderDriver, orderStore) = createDriver("orders", db, requestLog, orderResponses)
        val (paymentDriver, paymentStore) = createDriver("payments", db, requestLog, paymentResponses)

        // 1. Queue order CREATE offline.
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

        // 2. Queue payment CREATE offline with cross-service placeholder referencing order.
        paymentStore.insertLocalData(
            data = testItem(clientId = "payment-1", name = "Payment"),
            httpRequest = HttpRequest(
                method = HttpRequest.HttpMethod.POST,
                endpointUrl = "https://api.test.com/payments",
                requestBody = buildJsonObject {
                    put("client_id", "payment-1")
                    put(
                        "order_id",
                        HttpRequest.crossServiceServerIdPlaceholder("orders", "order-1")
                    )
                    put("amount", 5000)
                },
            ),
            idempotencyKey = "idem-payment-1",
            requestTag = TestRequestTag.DEFAULT,
        )

        // Act
        val coordinator = SyncUpCoordinator(
            drivers = listOf(orderDriver, paymentDriver),
            database = db,
        )
        val synced = coordinator.syncUpAll()

        // Assert
        assertEquals(2, synced, "Both requests should have synced")
        assertEquals(2, requestLog.size, "Two HTTP requests should have been made")
        assertEquals("orders", requestLog[0].first, "Order should sync first")
        assertEquals("payments", requestLog[1].first, "Payment should sync second")

        // Verify the payment request body contains the resolved order server ID.
        val paymentRequestBody = requestLog[1].second
        assertTrue(
            paymentRequestBody.contains(orderServerId),
            "Payment request body should contain the order's server ID ($orderServerId), got: $paymentRequestBody"
        )
        assertFalse(
            paymentRequestBody.contains("{cross:"),
            "Payment request body should not contain unresolved cross-service placeholders, got: $paymentRequestBody"
        )
    }

    /**
     * When the dependency hasn't synced yet (e.g., order CREATE failed),
     * the dependent request (payment) should be skipped.
     */
    @Test
    fun `syncUpAll skips requests with unresolved cross-service dependencies`() = runBlocking {
        val db = TestDatabaseFactory.createInMemory()
        val status = DataBuoyStatus(db, CoroutineScope(Dispatchers.Unconfined))
        val requestLog = mutableListOf<Pair<String, String>>()

        // Payment response won't be consumed because the request should be skipped.
        val paymentResponses = ArrayDeque(listOf(
            wrapResponse(testItem(clientId = "payment-1", serverId = "server-payment-99")),
        ))

        val (paymentDriver, paymentStore) = createDriver("payments", db, requestLog, paymentResponses)

        // Queue payment CREATE with cross-service placeholder, but DON'T create the order.
        paymentStore.insertLocalData(
            data = testItem(clientId = "payment-1", name = "Payment"),
            httpRequest = HttpRequest(
                method = HttpRequest.HttpMethod.POST,
                endpointUrl = "https://api.test.com/payments",
                requestBody = buildJsonObject {
                    put("client_id", "payment-1")
                    put(
                        "order_id",
                        HttpRequest.crossServiceServerIdPlaceholder("orders", "order-1")
                    )
                },
            ),
            idempotencyKey = "idem-payment-1",
            requestTag = TestRequestTag.DEFAULT,
        )

        val coordinator = SyncUpCoordinator(
            drivers = listOf(paymentDriver),
            database = db,
        )
        val synced = coordinator.syncUpAll()

        assertEquals(0, synced, "Payment should be skipped because order hasn't synced")
        assertEquals(0, requestLog.size, "No HTTP requests should have been made")
        assertEquals(1, status.pendingRequestCount.value, "Payment request should remain in queue")
    }

    // endregion
}
