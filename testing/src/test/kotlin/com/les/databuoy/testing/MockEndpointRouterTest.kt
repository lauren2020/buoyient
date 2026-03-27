package com.les.databuoy.testing

import com.les.databuoy.datatypes.HttpRequest
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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

        val client = router.buildHttpClient()
        val response = client.post("https://api.test.com/items") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("name", "Test") }.toString())
        }

        assertEquals(201, response.status.value)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(responseBody, body)
    }

    @Test
    fun `routes GET request to registered handler`() = runBlocking {
        val router = MockEndpointRouter()
        val responseBody = buildJsonObject { put("items", "[]") }
        router.onGet("https://api.test.com/items") { MockResponse(200, responseBody) }

        val client = router.buildHttpClient()
        val response = client.get("https://api.test.com/items")

        assertEquals(200, response.status.value)
    }

    @Test
    fun `unmatched request returns 404`() = runBlocking {
        val router = MockEndpointRouter()
        val client = router.buildHttpClient()

        val response = client.get("https://api.test.com/unknown")

        assertEquals(404, response.status.value)
    }

    @Test
    fun `wrong method does not match`() = runBlocking {
        val router = MockEndpointRouter()
        router.onPost("https://api.test.com/items") { MockResponse(201, JsonObject(emptyMap())) }

        val client = router.buildHttpClient()
        val response = client.get("https://api.test.com/items")

        assertEquals(404, response.status.value)
    }

    @Test
    fun `wildcard URL matching works through HttpClient`() = runBlocking {
        val router = MockEndpointRouter()
        router.onPut("https://api.test.com/items/*") { MockResponse(200, buildJsonObject { put("ok", true) }) }

        val client = router.buildHttpClient()
        val response = client.put("https://api.test.com/items/abc-123") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("name", "Updated") }.toString())
        }

        assertEquals(200, response.status.value)
    }

    // -- Request recording --

    @Test
    fun `records all requests in order`() = runBlocking {
        val router = MockEndpointRouter()
        router.onPost("https://api.test.com/items") { MockResponse(201, JsonObject(emptyMap())) }
        router.onGet("https://api.test.com/items") { MockResponse(200, JsonObject(emptyMap())) }

        val client = router.buildHttpClient()

        client.post("https://api.test.com/items") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("a", 1) }.toString())
        }
        client.get("https://api.test.com/items")

        assertEquals(2, router.requestLog.size)
        assertEquals(HttpRequest.HttpMethod.POST, router.requestLog[0].method)
        assertEquals(HttpRequest.HttpMethod.GET, router.requestLog[1].method)
    }

    @Test
    fun `clearRequestLog resets the log`() = runBlocking {
        val router = MockEndpointRouter()
        router.onGet("https://api.test.com/items") { MockResponse(200, JsonObject(emptyMap())) }

        val client = router.buildHttpClient()
        client.get("https://api.test.com/items")

        assertEquals(1, router.requestLog.size)
        router.clearRequestLog()
        assertEquals(0, router.requestLog.size)
    }

    // -- Connection error simulation --

    @Test
    fun `MockConnectionException produces IOException`() = runBlocking {
        val router = MockEndpointRouter()
        router.onPost("https://api.test.com/items") { throw MockConnectionException() }

        val client = router.buildHttpClient()
        val threw = try {
            client.post("https://api.test.com/items") {
                contentType(ContentType.Application.Json)
                setBody(JsonObject(emptyMap()).toString())
            }
            false
        } catch (e: java.io.IOException) {
            true
        }

        assertTrue(threw, "Expected IOException from MockConnectionException")
    }

    // -- Static response convenience --

    @Test
    fun `static response overload works`() = runBlocking {
        val router = MockEndpointRouter()
        val body = buildJsonObject { put("status", "ok") }
        router.on(HttpRequest.HttpMethod.GET, "https://api.test.com/health", 200, body)

        val client = router.buildHttpClient()
        val response = client.get("https://api.test.com/health")

        assertEquals(200, response.status.value)
        val responseBody = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(body, responseBody)
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
        val client = router.buildHttpClient()
        client.post("https://api.test.com/items") {
            contentType(ContentType.Application.Json)
            setBody(sentBody.toString())
        }

        assertEquals(sentBody, receivedBody)
    }

    // -- First matching route wins --

    @Test
    fun `first matching route wins`() = runBlocking {
        val router = MockEndpointRouter()
        router.onGet("https://api.test.com/items") { MockResponse(200, buildJsonObject { put("from", "first") }) }
        router.onGet("https://api.test.com/items") { MockResponse(200, buildJsonObject { put("from", "second") }) }

        val client = router.buildHttpClient()
        val response = client.get("https://api.test.com/items")

        assertEquals(200, response.status.value)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("first", body["from"].toString().trim('"'))
    }
}
