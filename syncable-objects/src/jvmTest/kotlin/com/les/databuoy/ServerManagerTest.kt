package com.les.databuoy

import com.les.databuoy.globalconfigs.GlobalHeaderProvider
import com.les.databuoy.managers.ServerManager
import com.les.databuoy.syncableobjectservicedatatypes.HttpRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServerManagerTest {

    // region helpers

    private fun makeServerManager(
        engine: MockEngine,
        serviceBaseHeaders: List<Pair<String, String>> = emptyList(),
        globalHeaderProvider: GlobalHeaderProvider? = null,
    ): ServerManager {
        val client = HttpClient(engine)
        return ServerManager(
            serviceBaseHeaders = serviceBaseHeaders,
            globalHeaderProvider = globalHeaderProvider,
            httpClient = client,
        )
    }

    private fun makeHttpRequest(
        method: HttpRequest.HttpMethod = HttpRequest.HttpMethod.POST,
        endpointUrl: String = "https://api.example.com/items",
        requestBody: JsonObject = buildJsonObject { put("name", "test") },
        additionalHeaders: List<Pair<String, String>> = emptyList(),
    ) = HttpRequest(
        method = method,
        endpointUrl = endpointUrl,
        requestBody = requestBody,
        additionalHeaders = additionalHeaders,
    )

    // endregion

    @Test
    fun `sendRequest returns Success on successful JSON response`() = runBlocking {
        val engine = MockEngine { _ ->
            respond(
                content = """{"id": "abc-123", "name": "test"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val serverManager = makeServerManager(engine)
        val request = makeHttpRequest(
            method = HttpRequest.HttpMethod.POST,
            requestBody = buildJsonObject { put("name", "test") },
        )

        val result = serverManager.sendRequest(request)

        assertIs<ServerManager.ServerManagerResponse.Success>(result)
        assertEquals(200, result.statusCode)
        assertEquals("abc-123", result.responseBody["id"]?.jsonPrimitive?.content)
        assertEquals("test", result.responseBody["name"]?.jsonPrimitive?.content)
        assertTrue(result.responseEpochTimestamp > 0)
    }

    @Test
    fun `sendRequest returns Success for GET request`() = runBlocking {
        var capturedMethod: String? = null
        val engine = MockEngine { requestData ->
            capturedMethod = requestData.method.value
            respond(
                content = """{"results": []}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val serverManager = makeServerManager(engine)
        val request = makeHttpRequest(method = HttpRequest.HttpMethod.GET)

        val result = serverManager.sendRequest(request)

        assertIs<ServerManager.ServerManagerResponse.Success>(result)
        assertEquals(200, result.statusCode)
        assertEquals("GET", capturedMethod)
    }

    @Test
    fun `sendRequest returns ConnectionError on IOException`() = runBlocking {
        val engine = MockEngine { _ ->
            throw java.io.IOException("Network unreachable")
        }
        val serverManager = makeServerManager(engine)
        val request = makeHttpRequest()

        val result = serverManager.sendRequest(request)

        assertIs<ServerManager.ServerManagerResponse.ConnectionError>(result)
        Unit
    }

    @Test
    fun `sendRequest returns empty JsonObject for non-JSON response`() = runBlocking {
        val engine = MockEngine { _ ->
            respond(
                content = "<html><body>Not Found</body></html>",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/html"),
            )
        }
        val serverManager = makeServerManager(engine)
        val request = makeHttpRequest()

        val result = serverManager.sendRequest(request)

        assertIs<ServerManager.ServerManagerResponse.Success>(result)
        assertEquals(JsonObject(emptyMap()), result.responseBody)
    }

    @Test
    fun `sendRequest applies base headers and additional headers`() = runBlocking {
        var capturedHeaders: Map<String, List<String>> = emptyMap()
        val engine = MockEngine { requestData ->
            capturedHeaders = requestData.headers.entries().associate { (key, values) -> key to values }
            respond(
                content = """{}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val serverManager = makeServerManager(
            engine = engine,
            serviceBaseHeaders = listOf("Authorization" to "Bearer token-123", "X-Api-Version" to "2"),
        )
        val request = makeHttpRequest(
            additionalHeaders = listOf("X-Request-Id" to "req-456"),
        )

        serverManager.sendRequest(request)

        assertTrue(capturedHeaders["Authorization"]?.contains("Bearer token-123") == true)
        assertTrue(capturedHeaders["X-Api-Version"]?.contains("2") == true)
        assertTrue(capturedHeaders["X-Request-Id"]?.contains("req-456") == true)
    }

    @Test
    fun `sendRequest applies global header provider headers before service and request headers`() = runBlocking {
        var capturedHeaders: Map<String, List<String>> = emptyMap()
        val engine = MockEngine { requestData ->
            capturedHeaders = requestData.headers.entries().associate { (key, values) -> key to values }
            respond(
                content = """{}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val serverManager = makeServerManager(
            engine = engine,
            serviceBaseHeaders = listOf("X-Service" to "todo"),
            globalHeaderProvider = GlobalHeaderProvider {
                listOf("Authorization" to "Bearer global-token")
            },
        )
        val request = makeHttpRequest(
            additionalHeaders = listOf("X-Request-Id" to "req-789"),
        )

        serverManager.sendRequest(request)

        assertTrue(capturedHeaders["Authorization"]?.contains("Bearer global-token") == true)
        assertTrue(capturedHeaders["X-Service"]?.contains("todo") == true)
        assertTrue(capturedHeaders["X-Request-Id"]?.contains("req-789") == true)
    }

    @Test
    fun `global header provider is evaluated on every request`() = runBlocking {
        var callCount = 0
        val engine = MockEngine { _ ->
            respond(
                content = """{}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val serverManager = makeServerManager(
            engine = engine,
            globalHeaderProvider = GlobalHeaderProvider {
                callCount++
                listOf("Authorization" to "Bearer token-$callCount")
            },
        )

        serverManager.sendRequest(makeHttpRequest())
        assertEquals(1, callCount)

        serverManager.sendRequest(makeHttpRequest())
        assertEquals(2, callCount)
    }

    @Test
    fun `sendRequest returns correct status code for error responses`() = runBlocking {
        val engine404 = MockEngine { _ ->
            respond(
                content = """{"error": "not found"}""",
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val serverManager404 = makeServerManager(engine404)
        val request = makeHttpRequest()

        val result404 = serverManager404.sendRequest(request)
        assertIs<ServerManager.ServerManagerResponse.Failed>(result404)
        assertEquals(404, result404.statusCode)

        val engine500 = MockEngine { _ ->
            respond(
                content = """{"error": "internal server error"}""",
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val serverManager500 = makeServerManager(engine500)

        val result500 = serverManager500.sendRequest(request)
        assertIs<ServerManager.ServerManagerResponse.ServerError>(result500)
        assertEquals(500, result500.statusCode)
    }
}
