package com.les.databuoy.testing

import com.les.databuoy.HttpRequest
import com.les.databuoy.ServerManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MockEndpointRouterTest {

    // -- URL pattern matching --

    @Test
    fun `exact URL match`() {
        assertTrue(MockEndpointRouter.matchesPattern("https://api.test.com/items", "https://api.test.com/items"))
    }

    @Test
    fun `exact URL mismatch`() {
        assertTrue(!MockEndpointRouter.matchesPattern("https://api.test.com/items", "https://api.test.com/other"))
    }

    @Test
    fun `trailing wildcard matches prefix`() {
        assertTrue(MockEndpointRouter.matchesPattern("https://api.test.com/items/*", "https://api.test.com/items/123"))
    }

    @Test
    fun `trailing wildcard does not match different prefix`() {
        assertTrue(!MockEndpointRouter.matchesPattern("https://api.test.com/items/*", "https://api.test.com/other/123"))
    }

    @Test
    fun `leading wildcard matches suffix`() {
        assertTrue(MockEndpointRouter.matchesPattern("*/items", "https://api.test.com/items"))
    }

    @Test
    fun `both wildcards match substring`() {
        assertTrue(MockEndpointRouter.matchesPattern("*/items/*", "https://api.test.com/items/123"))
    }

    // -- Routing --

    @Test
    fun `routes POST request to registered handler`() = runBlocking {
        val router = MockEndpointRouter()
        val responseBody = buildJsonObject { put("id", "srv-1") }
        router.onPost("https://api.test.com/items") { MockResponse(201, responseBody) }

        val serverManager = router.buildServerManager()
        val response = serverManager.sendRequest(
            HttpRequest(
                method = HttpRequest.HttpMethod.POST,
                endpointUrl = "https://api.test.com/items",
                requestBody = buildJsonObject { put("name", "Test") },
            )
        )

        assertTrue(response is ServerManager.ServerManagerResponse.Success)
        assertEquals(201, response.statusCode)
        assertEquals(responseBody, response.responseBody)
    }

    @Test
    fun `routes GET request to registered handler`() = runBlocking {
        val router = MockEndpointRouter()
        val responseBody = buildJsonObject { put("items", "[]") }
        router.onGet("https://api.test.com/items") { MockResponse(200, responseBody) }

        val serverManager = router.buildServerManager()
        val response = serverManager.sendRequest(
            HttpRequest(
                method = HttpRequest.HttpMethod.GET,
                endpointUrl = "https://api.test.com/items",
                requestBody = JsonObject(emptyMap()),
            )
        )

        assertTrue(response is ServerManager.ServerManagerResponse.Success)
        assertEquals(200, response.statusCode)
    }

    @Test
    fun `unmatched request returns 404`() = runBlocking {
        val router = MockEndpointRouter()
        val serverManager = router.buildServerManager()

        val response = serverManager.sendRequest(
            HttpRequest(
                method = HttpRequest.HttpMethod.GET,
                endpointUrl = "https://api.test.com/unknown",
                requestBody = JsonObject(emptyMap()),
            )
        )

        assertTrue(response is ServerManager.ServerManagerResponse.Failed)
        assertEquals(404, response.statusCode)
    }

    @Test
    fun `wrong method does not match`() = runBlocking {
        val router = MockEndpointRouter()
        router.onPost("https://api.test.com/items") { MockResponse(201, JsonObject(emptyMap())) }

        val serverManager = router.buildServerManager()
        val response = serverManager.sendRequest(
            HttpRequest(
                method = HttpRequest.HttpMethod.GET,
                endpointUrl = "https://api.test.com/items",
                requestBody = JsonObject(emptyMap()),
            )
        )

        assertTrue(response is ServerManager.ServerManagerResponse.Failed)
        assertEquals(404, response.statusCode)
    }

    @Test
    fun `wildcard URL matching works through ServerManager`() = runBlocking {
        val router = MockEndpointRouter()
        router.onPut("https://api.test.com/items/*") { MockResponse(200, buildJsonObject { put("ok", true) }) }

        val serverManager = router.buildServerManager()
        val response = serverManager.sendRequest(
            HttpRequest(
                method = HttpRequest.HttpMethod.PUT,
                endpointUrl = "https://api.test.com/items/abc-123",
                requestBody = buildJsonObject { put("name", "Updated") },
            )
        )

        assertTrue(response is ServerManager.ServerManagerResponse.Success)
        assertEquals(200, response.statusCode)
    }

    // -- Request recording --

    @Test
    fun `records all requests in order`() = runBlocking {
        val router = MockEndpointRouter()
        router.onPost("https://api.test.com/items") { MockResponse(201, JsonObject(emptyMap())) }
        router.onGet("https://api.test.com/items") { MockResponse(200, JsonObject(emptyMap())) }

        val serverManager = router.buildServerManager()

        serverManager.sendRequest(
            HttpRequest(HttpRequest.HttpMethod.POST, "https://api.test.com/items", buildJsonObject { put("a", 1) })
        )
        serverManager.sendRequest(
            HttpRequest(HttpRequest.HttpMethod.GET, "https://api.test.com/items", JsonObject(emptyMap()))
        )

        assertEquals(2, router.requestLog.size)
        assertEquals(HttpRequest.HttpMethod.POST, router.requestLog[0].method)
        assertEquals(HttpRequest.HttpMethod.GET, router.requestLog[1].method)
    }

    @Test
    fun `clearRequestLog resets the log`() = runBlocking {
        val router = MockEndpointRouter()
        router.onGet("https://api.test.com/items") { MockResponse(200, JsonObject(emptyMap())) }

        val serverManager = router.buildServerManager()
        serverManager.sendRequest(
            HttpRequest(HttpRequest.HttpMethod.GET, "https://api.test.com/items", JsonObject(emptyMap()))
        )

        assertEquals(1, router.requestLog.size)
        router.clearRequestLog()
        assertEquals(0, router.requestLog.size)
    }

    // -- Connection error simulation --

    @Test
    fun `MockConnectionException produces ConnectionError`() = runBlocking {
        val router = MockEndpointRouter()
        router.onPost("https://api.test.com/items") { throw MockConnectionException() }

        val serverManager = router.buildServerManager()
        val response = serverManager.sendRequest(
            HttpRequest(HttpRequest.HttpMethod.POST, "https://api.test.com/items", JsonObject(emptyMap()))
        )

        assertTrue(response is ServerManager.ServerManagerResponse.ConnectionError)
    }

    // -- Static response convenience --

    @Test
    fun `static response overload works`() = runBlocking {
        val router = MockEndpointRouter()
        val body = buildJsonObject { put("status", "ok") }
        router.on(HttpRequest.HttpMethod.GET, "https://api.test.com/health", 200, body)

        val serverManager = router.buildServerManager()
        val response = serverManager.sendRequest(
            HttpRequest(HttpRequest.HttpMethod.GET, "https://api.test.com/health", JsonObject(emptyMap()))
        )

        assertTrue(response is ServerManager.ServerManagerResponse.Success)
        assertEquals(200, response.statusCode)
        assertEquals(body, response.responseBody)
    }

    // -- Handler receives request body --

    @Test
    fun `handler receives the request body`() = runBlocking {
        val router = MockEndpointRouter()
        var receivedBody: JsonObject? = null
        router.onPost("https://api.test.com/items") { request ->
            receivedBody = request.body
            MockResponse(200, JsonObject(emptyMap()))
        }

        val sentBody = buildJsonObject { put("name", "Test Item") }
        val serverManager = router.buildServerManager()
        serverManager.sendRequest(
            HttpRequest(HttpRequest.HttpMethod.POST, "https://api.test.com/items", sentBody)
        )

        assertEquals(sentBody, receivedBody)
    }

    // -- First matching route wins --

    @Test
    fun `first matching route wins`() = runBlocking {
        val router = MockEndpointRouter()
        router.onGet("https://api.test.com/items") { MockResponse(200, buildJsonObject { put("from", "first") }) }
        router.onGet("https://api.test.com/items") { MockResponse(200, buildJsonObject { put("from", "second") }) }

        val serverManager = router.buildServerManager()
        val response = serverManager.sendRequest(
            HttpRequest(HttpRequest.HttpMethod.GET, "https://api.test.com/items", JsonObject(emptyMap()))
        )

        assertTrue(response is ServerManager.ServerManagerResponse.Success)
        assertEquals("first", response.responseBody["from"].toString().trim('"'))
    }
}
