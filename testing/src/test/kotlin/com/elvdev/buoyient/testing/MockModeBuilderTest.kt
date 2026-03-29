package com.elvdev.buoyient.testing

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MockModeBuilderTest {

    @Test
    fun `seedFile loads records from classpath JSON`() {
        val server = object : MockServiceServer() {
            override val name = "notes"
            override val seedFile = "seeds/notes.json"

            override fun registerHandlers(
                router: MockEndpointRouter,
                collection: MockServerCollection,
            ) {
                router.registerCrudHandlers(
                    collection = collection,
                    baseUrl = "https://api.example.com/v1/notes",
                )
            }
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

            override fun registerHandlers(
                router: MockEndpointRouter,
                collection: MockServerCollection,
            ) { }
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

            override fun registerHandlers(
                router: MockEndpointRouter,
                collection: MockServerCollection,
            ) {
                router.registerCrudHandlers(
                    collection = collection,
                    baseUrl = "https://api.example.com/v1/tasks",
                )
            }
        }

        val handle = MockModeBuilder()
            .service(server)
            .logging(false)
            .install()

        val collection = handle.store.collection("tasks")
        assertEquals(2, collection.getAll().size)
    }

    @Test
    fun `registerHandlers is called with pre-seeded collection`() {
        var handlerCollectionSize = -1

        val server = object : MockServiceServer() {
            override val name = "items"
            override val seeds = listOf(
                SeedEntry(data = buildJsonObject { put("name", "Item A") }),
            )

            override fun registerHandlers(
                router: MockEndpointRouter,
                collection: MockServerCollection,
            ) {
                handlerCollectionSize = collection.getAll().size
            }
        }

        MockModeBuilder()
            .service(server)
            .logging(false)
            .install()

        assertEquals(1, handlerCollectionSize)
    }

    @Test
    fun `multiple services are registered independently`() {
        val noteServer = object : MockServiceServer() {
            override val name = "notes"
            override val seeds = listOf(
                SeedEntry(data = buildJsonObject { put("title", "Note") }),
            )

            override fun registerHandlers(
                router: MockEndpointRouter,
                collection: MockServerCollection,
            ) { }
        }

        val taskServer = object : MockServiceServer() {
            override val name = "tasks"
            override val seeds = listOf(
                SeedEntry(data = buildJsonObject { put("title", "Task A") }),
                SeedEntry(data = buildJsonObject { put("title", "Task B") }),
            )

            override fun registerHandlers(
                router: MockEndpointRouter,
                collection: MockServerCollection,
            ) { }
        }

        val handle = MockModeBuilder()
            .service(noteServer)
            .service(taskServer)
            .logging(false)
            .install()

        assertEquals(1, handle.store.collection("notes").getAll().size)
        assertEquals(2, handle.store.collection("tasks").getAll().size)
    }
}
