package com.elvdev.buoyient.testing

import com.elvdev.buoyient.datatypes.HttpRequest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MockModeBuilderTest {

    @Test
    fun `seedFile loads records from classpath JSON`() {
        val server = object : MockServiceServer() {
            override val name = "notes"
            override val seedFile = "seeds/notes.json"

            override fun endpoints(collection: MockServerCollection): List<MockEndpoint> =
                crudEndpoints(
                    collection = collection,
                    baseUrl = "https://api.example.com/v1/notes",
                )
        }

        val handle = MockModeBuilder()
            .service(server)
            .logging(false)
            .install()

        val collection = handle.store.collection("notes")
        val records = collection.getAll()

        assertEquals(2, records.size)
        assertEquals("Welcome", records[0].data["title"]?.jsonPrimitive?.content)
        assertEquals("Hello world", records[0].data["body"]?.jsonPrimitive?.content)
        assertEquals("Second note", records[1].data["title"]?.jsonPrimitive?.content)
    }

    @Test
    fun `seedFile throws on missing classpath resource`() {
        val server = object : MockServiceServer() {
            override val name = "notes"
            override val seedFile = "seeds/nonexistent.json"

            override fun endpoints(collection: MockServerCollection): List<MockEndpoint> =
                emptyList()
        }

        val builder = MockModeBuilder()
            .service(server)
            .logging(false)

        val ex = assertFailsWith<IllegalStateException> { builder.install() }
        assert(ex.message!!.contains("nonexistent.json"))
    }

    @Test
    fun `in-memory seeds work`() {
        val server = object : MockServiceServer() {
            override val name = "tasks"
            override val seeds = listOf(
                SeedEntry(data = buildJsonObject { put("title", "Task A") }),
                SeedEntry(data = buildJsonObject { put("title", "Task B") }),
            )

            override fun endpoints(collection: MockServerCollection): List<MockEndpoint> =
                crudEndpoints(
                    collection = collection,
                    baseUrl = "https://api.example.com/v1/tasks",
                )
        }

        val handle = MockModeBuilder()
            .service(server)
            .logging(false)
            .install()

        val collection = handle.store.collection("tasks")
        assertEquals(2, collection.getAll().size)
    }

    @Test
    fun `endpoints is called with pre-seeded collection`() {
        var endpointsCollectionSize = -1

        val server = object : MockServiceServer() {
            override val name = "items"
            override val seeds = listOf(
                SeedEntry(data = buildJsonObject { put("name", "Item A") }),
            )

            override fun endpoints(collection: MockServerCollection): List<MockEndpoint> {
                endpointsCollectionSize = collection.getAll().size
                return emptyList()
            }
        }

        MockModeBuilder()
            .service(server)
            .logging(false)
            .install()

        assertEquals(1, endpointsCollectionSize)
    }

    @Test
    fun `multiple services are registered independently`() {
        val noteServer = object : MockServiceServer() {
            override val name = "notes"
            override val seeds = listOf(
                SeedEntry(data = buildJsonObject { put("title", "Note") }),
            )

            override fun endpoints(collection: MockServerCollection): List<MockEndpoint> =
                emptyList()
        }

        val taskServer = object : MockServiceServer() {
            override val name = "tasks"
            override val seeds = listOf(
                SeedEntry(data = buildJsonObject { put("title", "Task A") }),
                SeedEntry(data = buildJsonObject { put("title", "Task B") }),
            )

            override fun endpoints(collection: MockServerCollection): List<MockEndpoint> =
                emptyList()
        }

        val handle = MockModeBuilder()
            .service(noteServer)
            .service(taskServer)
            .logging(false)
            .install()

        assertEquals(1, handle.store.collection("notes").getAll().size)
        assertEquals(2, handle.store.collection("tasks").getAll().size)
    }

    @Test
    fun `endpointIndex contains declared endpoints keyed by service name`() {
        val noteServer = object : MockServiceServer() {
            override val name = "notes"

            override fun endpoints(collection: MockServerCollection): List<MockEndpoint> =
                crudEndpoints(
                    collection = collection,
                    baseUrl = "https://api.example.com/v1/notes",
                )
        }

        val taskServer = object : MockServiceServer() {
            override val name = "tasks"

            override fun endpoints(collection: MockServerCollection): List<MockEndpoint> =
                crudEndpoints(
                    collection = collection,
                    baseUrl = "https://api.example.com/v1/tasks",
                ) + syncDownEndpoint(
                    collection = collection,
                    urlPattern = "https://api.example.com/v1/tasks/sync",
                )
        }

        val handle = MockModeBuilder()
            .service(noteServer)
            .service(taskServer)
            .logging(false)
            .install()

        // Notes: 5 CRUD endpoints
        val noteEndpoints = handle.endpointIndex["notes"]!!
        assertEquals(5, noteEndpoints.size)
        assertEquals(
            listOf("create", "update", "get", "list", "void"),
            noteEndpoints.map { it.label },
        )

        // Tasks: 5 CRUD + 1 sync-down
        val taskEndpoints = handle.endpointIndex["tasks"]!!
        assertEquals(6, taskEndpoints.size)
        assertEquals("sync-down", taskEndpoints.last().label)

        // Verify method/pattern on a specific endpoint
        val createNote = noteEndpoints.first { it.label == "create" }
        assertEquals(HttpRequest.HttpMethod.POST, createNote.method)
        assertEquals("https://api.example.com/v1/notes", createNote.urlPattern)
    }

    @Test
    fun `crudEndpoints returns 5 labeled endpoints`() {
        val store = MockServerStore()
        val collection = store.collection("test")

        val endpoints = crudEndpoints(
            collection = collection,
            baseUrl = "https://api.example.com/items",
        )

        assertEquals(5, endpoints.size)
        assertEquals("create", endpoints[0].label)
        assertEquals(HttpRequest.HttpMethod.POST, endpoints[0].method)
        assertEquals("https://api.example.com/items", endpoints[0].urlPattern)

        assertEquals("update", endpoints[1].label)
        assertEquals(HttpRequest.HttpMethod.PUT, endpoints[1].method)

        assertEquals("get", endpoints[2].label)
        assertEquals(HttpRequest.HttpMethod.GET, endpoints[2].method)
        assertTrue(endpoints[2].urlPattern.endsWith("/*"))

        assertEquals("list", endpoints[3].label)
        assertEquals(HttpRequest.HttpMethod.GET, endpoints[3].method)

        assertEquals("void", endpoints[4].label)
        assertEquals(HttpRequest.HttpMethod.DELETE, endpoints[4].method)
    }
}
