package com.elvdev.buoyient.testing

import com.elvdev.buoyient.datatypes.HttpRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MockEndpointControllerTest {

    private val controller = MockEndpointController()
    private val store = MockServerStore()
    private val collection = store.collection("items")
    private val baseUrl = "https://api.example.com/items"

    private val createEndpoint = MockEndpoint(
        method = HttpRequest.HttpMethod.POST,
        urlPattern = baseUrl,
        label = "create",
    ) { request ->
        val record = collection.create(request.body)
        MockResponse(statusCode = 201, body = record.toJsonObject())
    }

    private val listEndpoint = MockEndpoint(
        method = HttpRequest.HttpMethod.GET,
        urlPattern = baseUrl,
        label = "list",
    ) { _ ->
        MockResponse(statusCode = 200, body = JsonObject(emptyMap()))
    }

    private fun dummyRequest(body: JsonObject = JsonObject(emptyMap())) = RecordedRequest(
        method = HttpRequest.HttpMethod.POST,
        url = baseUrl,
        body = body,
        headers = emptyMap(),
    )

    // -- Passthrough --

    @Test
    fun `no override passes through to real handler`() {
        val handler = controller.wrap("items", createEndpoint)
        val body = buildJsonObject { put("name", "Item A") }

        val response = handler.handle(dummyRequest(body))

        assertEquals(201, response.statusCode)
        assertEquals(1, collection.getAll().size)
    }

    // -- ServerError --

    @Test
    fun `ServerError runs handler then returns error status`() {
        controller.setOverride("items", "create", FailureOverride.ServerError())
        val handler = controller.wrap("items", createEndpoint)
        val body = buildJsonObject { put("name", "Item A") }

        val response = handler.handle(dummyRequest(body))

        // Real handler ran — record was created
        assertEquals(1, collection.getAll().size)
        // But response is the error
        assertEquals(500, response.statusCode)
    }

    @Test
    fun `ServerError with custom status code`() {
        controller.setOverride("items", "create", FailureOverride.ServerError(503))
        val handler = controller.wrap("items", createEndpoint)

        val response = handler.handle(dummyRequest())

        assertEquals(503, response.statusCode)
    }

    // -- Timeout (serverReceivedRequest = false) --

    @Test
    fun `Timeout pre-server skips handler and throws MockConnectionException`() {
        controller.setOverride("items", "create", FailureOverride.Timeout(serverReceivedRequest = false))
        val handler = controller.wrap("items", createEndpoint)

        assertFailsWith<MockConnectionException> {
            handler.handle(dummyRequest(buildJsonObject { put("name", "Item A") }))
        }

        // Real handler did NOT run
        assertEquals(0, collection.getAll().size)
    }

    @Test
    fun `Timeout defaults to serverReceivedRequest = false`() {
        controller.setOverride("items", "create", FailureOverride.Timeout())
        val handler = controller.wrap("items", createEndpoint)

        assertFailsWith<MockConnectionException> {
            handler.handle(dummyRequest())
        }

        assertEquals(0, collection.getAll().size)
    }

    // -- Timeout (serverReceivedRequest = true) --

    @Test
    fun `Timeout post-server runs handler then throws MockTimeoutException`() {
        controller.setOverride("items", "create", FailureOverride.Timeout(serverReceivedRequest = true))
        val handler = controller.wrap("items", createEndpoint)

        assertFailsWith<MockTimeoutException> {
            handler.handle(dummyRequest(buildJsonObject { put("name", "Item A") }))
        }

        // Real handler ran — record was created
        assertEquals(1, collection.getAll().size)
    }

    // -- Override precedence --

    @Test
    fun `per-endpoint override takes precedence over global`() {
        controller.setGlobalOverride(FailureOverride.Timeout())
        controller.setOverride("items", "create", FailureOverride.ServerError(503))

        val createHandler = controller.wrap("items", createEndpoint)
        val listHandler = controller.wrap("items", listEndpoint)

        // "create" has per-endpoint override → ServerError
        val createResponse = createHandler.handle(dummyRequest())
        assertEquals(503, createResponse.statusCode)

        // "list" falls through to global → Timeout
        assertFailsWith<MockConnectionException> {
            listHandler.handle(dummyRequest())
        }
    }

    @Test
    fun `global override applies when no per-endpoint override`() {
        controller.setGlobalOverride(FailureOverride.ServerError(502))

        val handler = controller.wrap("items", createEndpoint)
        val response = handler.handle(dummyRequest())

        assertEquals(502, response.statusCode)
    }

    // -- Clear behavior --

    @Test
    fun `clearOverride restores passthrough for that endpoint`() {
        controller.setOverride("items", "create", FailureOverride.Timeout())
        controller.clearOverride("items", "create")

        val handler = controller.wrap("items", createEndpoint)
        val response = handler.handle(dummyRequest(buildJsonObject { put("name", "Test") }))

        assertEquals(201, response.statusCode)
        assertEquals(1, collection.getAll().size)
    }

    @Test
    fun `clearAll removes all overrides`() {
        controller.setOverride("items", "create", FailureOverride.Timeout())
        controller.setGlobalOverride(FailureOverride.Timeout(serverReceivedRequest = true))
        controller.clearAll()

        val handler = controller.wrap("items", createEndpoint)
        val response = handler.handle(dummyRequest(buildJsonObject { put("name", "Test") }))

        assertEquals(201, response.statusCode)
    }

    // -- Service-level overrides --

    @Test
    fun `setServiceOverride applies to all endpoints in the list`() {
        val endpoints = listOf(createEndpoint, listEndpoint)
        controller.setServiceOverride("items", FailureOverride.ServerError(503), endpoints)

        val createHandler = controller.wrap("items", createEndpoint)
        val listHandler = controller.wrap("items", listEndpoint)

        assertEquals(503, createHandler.handle(dummyRequest()).statusCode)
        assertEquals(503, listHandler.handle(dummyRequest()).statusCode)
    }

    @Test
    fun `clearServiceOverrides clears all endpoints in the list`() {
        val endpoints = listOf(createEndpoint, listEndpoint)
        controller.setServiceOverride("items", FailureOverride.Timeout(), endpoints)
        controller.clearServiceOverrides("items", endpoints)

        val handler = controller.wrap("items", createEndpoint)
        val response = handler.handle(dummyRequest(buildJsonObject { put("name", "Test") }))

        assertEquals(201, response.statusCode)
    }

    // -- activeOverride query --

    @Test
    fun `activeOverride returns null when nothing is set`() {
        assertNull(controller.activeOverride("items", "create"))
    }

    @Test
    fun `activeOverride returns per-endpoint override`() {
        controller.setOverride("items", "create", FailureOverride.Timeout(serverReceivedRequest = true))

        assertEquals(
            FailureOverride.Timeout(serverReceivedRequest = true),
            controller.activeOverride("items", "create"),
        )
        assertNull(controller.activeOverride("items", "list"))
    }

    @Test
    fun `activeOverride falls back to global`() {
        controller.setGlobalOverride(FailureOverride.Timeout())

        assertEquals(FailureOverride.Timeout(), controller.activeOverride("items", "create"))
    }
}
