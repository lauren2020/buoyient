package com.les.databuoy.testing

import io.ktor.client.request.delete
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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MockServerStoreTest {

    // -------------------------------------------------------------------------
    // MockServerCollection — CRUD
    // -------------------------------------------------------------------------

    @Test
    fun `create assigns serverId and version 1`() {
        val store = MockServerStore()
        val todos = store.collection("todos")

        val record = todos.create(buildJsonObject { put("title", "Buy milk") })

        assertEquals("server-1", record.serverId)
        assertEquals(1, record.version)
        assertFalse(record.voided)
    }

    @Test
    fun `create extracts clientId from request body`() {
        val store = MockServerStore()
        val todos = store.collection("todos")

        val record = todos.create(buildJsonObject {
            put("title", "Buy milk")
            put("client_id", "client-42")
        })

        assertEquals("client-42", record.clientId)
    }

    @Test
    fun `create extracts clientId from reference_id field`() {
        val store = MockServerStore()
        val todos = store.collection("todos")

        val record = todos.create(buildJsonObject {
            put("title", "Buy milk")
            put("reference_id", "ref-99")
        })

        assertEquals("ref-99", record.clientId)
    }

    @Test
    fun `create stores the full request body as data`() {
        val store = MockServerStore()
        val todos = store.collection("todos")

        val body = buildJsonObject {
            put("title", "Buy milk")
            put("completed", false)
        }
        val record = todos.create(body)

        assertEquals("Buy milk", record.data["title"]?.jsonPrimitive?.content)
        assertEquals("false", record.data["completed"]?.jsonPrimitive?.content)
    }

    @Test
    fun `sequential creates produce incrementing server IDs`() {
        val store = MockServerStore()
        val todos = store.collection("todos")

        val r1 = todos.create(buildJsonObject { put("title", "First") })
        val r2 = todos.create(buildJsonObject { put("title", "Second") })

        assertEquals("server-1", r1.serverId)
        assertEquals("server-2", r2.serverId)
    }

    @Test
    fun `update merges fields and increments version`() {
        val store = MockServerStore()
        val todos = store.collection("todos")

        val created = todos.create(buildJsonObject {
            put("title", "Buy milk")
            put("completed", false)
        })

        val updated = todos.update(created.serverId, buildJsonObject {
            put("completed", true)
        })

        assertNotNull(updated)
        assertEquals(2, updated.version)
        // Original field preserved
        assertEquals("Buy milk", updated.data["title"]?.jsonPrimitive?.content)
        // Updated field changed
        assertEquals("true", updated.data["completed"]?.jsonPrimitive?.content)
    }

    @Test
    fun `update returns null for unknown serverId`() {
        val store = MockServerStore()
        val todos = store.collection("todos")

        val result = todos.update("nonexistent", buildJsonObject { put("title", "nope") })

        assertNull(result)
    }

    @Test
    fun `get returns record by serverId`() {
        val store = MockServerStore()
        val todos = store.collection("todos")

        val created = todos.create(buildJsonObject { put("title", "Buy milk") })
        val fetched = todos.get(created.serverId)

        assertNotNull(fetched)
        assertEquals(created.serverId, fetched.serverId)
    }

    @Test
    fun `get returns null for unknown serverId`() {
        val store = MockServerStore()
        val todos = store.collection("todos")

        assertNull(todos.get("nonexistent"))
    }

    @Test
    fun `getAll returns all records including voided`() {
        val store = MockServerStore()
        val todos = store.collection("todos")

        val r1 = todos.create(buildJsonObject { put("title", "First") })
        todos.create(buildJsonObject { put("title", "Second") })
        todos.void(r1.serverId)

        val all = todos.getAll()
        assertEquals(2, all.size)
        assertTrue(all.any { it.voided })
    }

    @Test
    fun `getUpdatedSince filters by timestamp`() {
        var now = 1000L
        val store = MockServerStore(clock = { now })
        val todos = store.collection("todos")

        todos.create(buildJsonObject { put("title", "Old") })
        val cutoff = now

        now = 2000L
        todos.create(buildJsonObject { put("title", "New") })

        val delta = todos.getUpdatedSince(cutoff)
        assertEquals(1, delta.size)
        assertEquals("New", delta[0].data["title"]?.jsonPrimitive?.content)
    }

    @Test
    fun `void marks record as voided and increments version`() {
        val store = MockServerStore()
        val todos = store.collection("todos")

        val created = todos.create(buildJsonObject { put("title", "Buy milk") })
        val voided = todos.void(created.serverId)

        assertNotNull(voided)
        assertTrue(voided.voided)
        assertEquals(2, voided.version)
    }

    @Test
    fun `void returns null for unknown serverId`() {
        val store = MockServerStore()
        val todos = store.collection("todos")

        assertNull(todos.void("nonexistent"))
    }

    // -------------------------------------------------------------------------
    // MockServerCollection — Test Setup API
    // -------------------------------------------------------------------------

    @Test
    fun `seed inserts record with explicit values`() {
        val store = MockServerStore()
        val todos = store.collection("todos")

        val record = todos.seed(
            serverId = "custom-1",
            data = buildJsonObject { put("title", "Seeded item") },
            version = 5,
            clientId = "client-1",
        )

        assertEquals("custom-1", record.serverId)
        assertEquals(5, record.version)
        assertEquals("client-1", record.clientId)

        // Verify it's actually in the store
        assertEquals(record, todos.get("custom-1"))
    }

    @Test
    fun `seed with full record inserts directly`() {
        val store = MockServerStore()
        val todos = store.collection("todos")

        val record = MockServerRecord(
            serverId = "pre-built",
            clientId = null,
            version = 3,
            data = buildJsonObject { put("title", "Pre-built") },
            voided = false,
            createdAt = 100L,
            updatedAt = 200L,
        )
        todos.seed(record)

        assertEquals(record, todos.get("pre-built"))
    }

    @Test
    fun `mutate transforms data and increments version`() {
        val store = MockServerStore()
        val todos = store.collection("todos")

        todos.seed("srv-1", buildJsonObject {
            put("title", "Buy milk")
            put("completed", false)
        })

        val mutated = todos.mutate("srv-1") { data ->
            buildJsonObject {
                data.forEach { (k, v) -> put(k, v) }
                put("title", "Buy oat milk")
            }
        }

        assertNotNull(mutated)
        assertEquals(2, mutated.version)
        assertEquals("Buy oat milk", mutated.data["title"]?.jsonPrimitive?.content)
    }

    @Test
    fun `mutate returns null for unknown serverId`() {
        val store = MockServerStore()
        val todos = store.collection("todos")

        assertNull(todos.mutate("nonexistent") { it })
    }

    @Test
    fun `remove hard-deletes a record`() {
        val store = MockServerStore()
        val todos = store.collection("todos")

        todos.create(buildJsonObject { put("title", "Delete me") })
        assertTrue(todos.remove("server-1"))
        assertNull(todos.get("server-1"))
        assertEquals(0, todos.count())
    }

    @Test
    fun `remove returns false for unknown serverId`() {
        val store = MockServerStore()
        val todos = store.collection("todos")

        assertFalse(todos.remove("nonexistent"))
    }

    @Test
    fun `findByClientId returns correct record`() {
        val store = MockServerStore()
        val todos = store.collection("todos")

        todos.create(buildJsonObject {
            put("title", "First")
            put("client_id", "c-1")
        })
        todos.create(buildJsonObject {
            put("title", "Second")
            put("client_id", "c-2")
        })

        val found = todos.findByClientId("c-2")
        assertNotNull(found)
        assertEquals("Second", found.data["title"]?.jsonPrimitive?.content)
    }

    @Test
    fun `findByClientId returns null when not found`() {
        val store = MockServerStore()
        val todos = store.collection("todos")

        assertNull(todos.findByClientId("nonexistent"))
    }

    @Test
    fun `count and isEmpty reflect collection state`() {
        val store = MockServerStore()
        val todos = store.collection("todos")

        assertTrue(todos.isEmpty())
        assertEquals(0, todos.count())

        todos.create(buildJsonObject { put("title", "Item") })

        assertFalse(todos.isEmpty())
        assertEquals(1, todos.count())
    }

    @Test
    fun `clear removes all records`() {
        val store = MockServerStore()
        val todos = store.collection("todos")

        todos.create(buildJsonObject { put("title", "First") })
        todos.create(buildJsonObject { put("title", "Second") })
        todos.clear()

        assertTrue(todos.isEmpty())
    }

    // -------------------------------------------------------------------------
    // MockServerStore — top-level
    // -------------------------------------------------------------------------

    @Test
    fun `collections are lazy-created`() {
        val store = MockServerStore()

        val c1 = store.collection("a")
        val c2 = store.collection("a")

        assertTrue(c1 === c2) // same instance
    }

    @Test
    fun `ID counter is shared across collections`() {
        val store = MockServerStore()

        val r1 = store.collection("todos").create(buildJsonObject { put("x", 1) })
        val r2 = store.collection("notes").create(buildJsonObject { put("x", 2) })

        assertEquals("server-1", r1.serverId)
        assertEquals("server-2", r2.serverId)
    }

    @Test
    fun `custom ID prefix`() {
        val store = MockServerStore(idPrefix = "srv")

        val record = store.collection("todos").create(buildJsonObject { put("x", 1) })

        assertEquals("srv-1", record.serverId)
    }

    @Test
    fun `reset clears all collections and resets counter`() {
        val store = MockServerStore()
        store.collection("todos").create(buildJsonObject { put("x", 1) })
        store.collection("notes").create(buildJsonObject { put("x", 2) })

        store.reset()

        // Counter reset — next ID starts at 1 again
        val record = store.collection("todos").create(buildJsonObject { put("x", 3) })
        assertEquals("server-1", record.serverId)
        // Old collection was cleared
        assertEquals(1, store.collection("todos").count())
    }

    // -------------------------------------------------------------------------
    // MockServerRecord — toJsonObject
    // -------------------------------------------------------------------------

    @Test
    fun `toJsonObject flattens metadata and data fields`() {
        val record = MockServerRecord(
            serverId = "srv-1",
            clientId = "c-1",
            version = 3,
            data = buildJsonObject {
                put("title", "Test")
                put("completed", true)
            },
            voided = false,
            createdAt = 1000L,
            updatedAt = 2000L,
        )

        val json = record.toJsonObject()

        assertEquals("srv-1", json["server_id"]?.jsonPrimitive?.content)
        assertEquals("c-1", json["client_id"]?.jsonPrimitive?.content)
        assertEquals(3, json["version"]?.jsonPrimitive?.int)
        assertEquals("false", json["voided"]?.jsonPrimitive?.content)
        assertEquals("Test", json["title"]?.jsonPrimitive?.content)
        assertEquals("true", json["completed"]?.jsonPrimitive?.content)
    }

    @Test
    fun `toJsonObject omits clientId when null`() {
        val record = MockServerRecord(
            serverId = "srv-1",
            clientId = null,
            version = 1,
            data = JsonObject(emptyMap()),
            createdAt = 1000L,
            updatedAt = 1000L,
        )

        val json = record.toJsonObject()

        assertNull(json["client_id"])
    }

    // -------------------------------------------------------------------------
    // Router Integration — registerCrudHandlers
    // -------------------------------------------------------------------------

    @Test
    fun `registerCrudHandlers wires POST create`() = runBlocking {
        val store = MockServerStore()
        val todos = store.collection("todos")
        val router = MockEndpointRouter()
        router.registerCrudHandlers(todos, "https://api.test.com/todos")
        val client = router.buildHttpClient()

        val response = client.post("https://api.test.com/todos") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("title", "New todo") }.toString())
        }

        assertEquals(201, response.status.value)
        // Record is in the store
        assertEquals(1, todos.count())
        assertEquals("New todo", todos.get("server-1")?.data?.get("title")?.jsonPrimitive?.content)
    }

    @Test
    fun `registerCrudHandlers wires PUT update`() = runBlocking {
        val store = MockServerStore()
        val todos = store.collection("todos")
        todos.seed("srv-1", buildJsonObject { put("title", "Original") })

        val router = MockEndpointRouter()
        router.registerCrudHandlers(todos, "https://api.test.com/todos")
        val client = router.buildHttpClient()

        val response = client.put("https://api.test.com/todos/srv-1") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("title", "Updated") }.toString())
        }

        assertEquals(200, response.status.value)
        assertEquals("Updated", todos.get("srv-1")?.data?.get("title")?.jsonPrimitive?.content)
        assertEquals(2, todos.get("srv-1")?.version)
    }

    @Test
    fun `registerCrudHandlers wires GET single`() = runBlocking {
        val store = MockServerStore()
        val todos = store.collection("todos")
        todos.seed("srv-1", buildJsonObject { put("title", "Existing") })

        val router = MockEndpointRouter()
        router.registerCrudHandlers(todos, "https://api.test.com/todos")
        val client = router.buildHttpClient()

        val response = client.get("https://api.test.com/todos/srv-1")

        assertEquals(200, response.status.value)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val data = body["data"]?.jsonObject
        assertNotNull(data)
        assertEquals("Existing", data["title"]?.jsonPrimitive?.content)
    }

    @Test
    fun `registerCrudHandlers returns 404 for unknown serverId on GET`() = runBlocking {
        val store = MockServerStore()
        val todos = store.collection("todos")

        val router = MockEndpointRouter()
        router.registerCrudHandlers(todos, "https://api.test.com/todos")
        val client = router.buildHttpClient()

        val response = client.get("https://api.test.com/todos/nonexistent")

        assertEquals(404, response.status.value)
    }

    @Test
    fun `registerCrudHandlers wires GET list`() = runBlocking {
        val store = MockServerStore()
        val todos = store.collection("todos")
        todos.seed("srv-1", buildJsonObject { put("title", "First") })
        todos.seed("srv-2", buildJsonObject { put("title", "Second") })

        val router = MockEndpointRouter()
        router.registerCrudHandlers(todos, "https://api.test.com/todos")
        val client = router.buildHttpClient()

        val response = client.get("https://api.test.com/todos")

        assertEquals(200, response.status.value)
    }

    @Test
    fun `registerCrudHandlers wires DELETE void`() = runBlocking {
        val store = MockServerStore()
        val todos = store.collection("todos")
        todos.seed("srv-1", buildJsonObject { put("title", "To delete") })

        val router = MockEndpointRouter()
        router.registerCrudHandlers(todos, "https://api.test.com/todos")
        val client = router.buildHttpClient()

        val response = client.delete("https://api.test.com/todos/srv-1")

        assertEquals(200, response.status.value)
        assertTrue(todos.get("srv-1")!!.voided)
    }

    @Test
    fun `registerCrudHandlers uses custom responseWrapper`() = runBlocking {
        val store = MockServerStore()
        val todos = store.collection("todos")

        val router = MockEndpointRouter()
        router.registerCrudHandlers(
            collection = todos,
            baseUrl = "https://api.test.com/todos",
            responseWrapper = { record ->
                buildJsonObject { put("item", record.toJsonObject()) }
            },
        )
        val client = router.buildHttpClient()

        val response = client.post("https://api.test.com/todos") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("title", "Test") }.toString())
        }

        assertEquals(201, response.status.value)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        // Response uses "item" key instead of default "data"
        assertNotNull(body["item"])
        assertNull(body["data"])
    }

    // -------------------------------------------------------------------------
    // Router Integration — registerSyncDownHandler
    // -------------------------------------------------------------------------

    @Test
    fun `registerSyncDownHandler returns all records when no timestamp`() = runBlocking {
        val store = MockServerStore()
        val todos = store.collection("todos")
        todos.seed("srv-1", buildJsonObject { put("title", "First") })
        todos.seed("srv-2", buildJsonObject { put("title", "Second") })

        val router = MockEndpointRouter()
        router.registerSyncDownHandler(
            collection = todos,
            urlPattern = "https://api.test.com/todos/sync",
        )
        val client = router.buildHttpClient()

        val response = client.post("https://api.test.com/todos/sync") {
            contentType(ContentType.Application.Json)
            setBody(JsonObject(emptyMap()).toString())
        }

        assertEquals(200, response.status.value)
    }

    @Test
    fun `registerSyncDownHandler returns delta by timestamp`() = runBlocking {
        var now = 1000L
        val store = MockServerStore(clock = { now })
        val todos = store.collection("todos")

        todos.seed("srv-1", buildJsonObject { put("title", "Old") })
        val cutoff = now

        now = 2000L
        todos.seed("srv-2", buildJsonObject { put("title", "New") })

        val router = MockEndpointRouter()
        router.registerSyncDownHandler(
            collection = todos,
            urlPattern = "https://api.test.com/todos/sync",
            extractTimestamp = { request ->
                request.body["last_synced_timestamp"]?.jsonPrimitive?.content?.toLongOrNull()
            },
        )
        val client = router.buildHttpClient()

        val response = client.post("https://api.test.com/todos/sync") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("last_synced_timestamp", cutoff.toString()) }.toString())
        }

        assertEquals(200, response.status.value)
        // Only the "New" record should be in the response (created at t=2000, cutoff=1000)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val records = body["data"]?.jsonArray
        assertNotNull(records, "Response should contain a 'data' array")
        assertEquals(1, records.size, "Only the record created after the cutoff should be returned")
        assertEquals("New", records[0].jsonObject["title"]?.jsonPrimitive?.content)
    }

    // -------------------------------------------------------------------------
    // TestServiceEnvironment integration
    // -------------------------------------------------------------------------

    @Test
    fun `TestServiceEnvironment includes mockServerStore`() {
        val env = TestServiceEnvironment()

        val todos = env.mockServerStore.collection("todos")
        todos.seed("srv-1", buildJsonObject { put("title", "Seeded") })

        assertEquals(1, todos.count())
    }
}
