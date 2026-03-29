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
        val handle = MockModeBuilder()
            .service(
                name = "notes",
                baseUrl = "https://api.example.com/v1/notes",
                seedFile = "seeds/notes.json",
            )
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
        val builder = MockModeBuilder()
            .service(
                name = "notes",
                baseUrl = "https://api.example.com/v1/notes",
                seedFile = "seeds/nonexistent.json",
            )
            .logging(false)

        val ex = assertFailsWith<IllegalStateException> { builder.install() }
        assert(ex.message!!.contains("nonexistent.json"))
    }

    @Test
    fun `in-memory seeds still work`() {
        val handle = MockModeBuilder()
            .service(
                name = "tasks",
                baseUrl = "https://api.example.com/v1/tasks",
                seeds = listOf(
                    buildJsonObject { put("title", "Task A") },
                    buildJsonObject { put("title", "Task B") },
                ),
            )
            .logging(false)
            .install()

        val collection = handle.store.collection("tasks")
        assertEquals(2, collection.getAll().size)
    }
}
