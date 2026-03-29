package com.elvdev.buoyient.testing

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class TestServiceEnvironmentTest {

    @Test
    fun `TestServiceEnvironment includes mockServerStore`() {
        val env = TestServiceEnvironment()

        val todos = env.mockServerStore.collection("todos")
        todos.seed("srv-1", buildJsonObject { put("title", "Seeded") })

        assertEquals(1, todos.count())
    }
}
