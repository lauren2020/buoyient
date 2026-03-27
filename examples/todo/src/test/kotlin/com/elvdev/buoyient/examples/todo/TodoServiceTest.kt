package com.elvdev.buoyient.examples.todo

import com.elvdev.buoyient.SyncableObject
import com.elvdev.buoyient.datatypes.SyncableObjectServiceResponse
import com.elvdev.buoyient.testing.MockResponse
import com.elvdev.buoyient.testing.TestServiceEnvironment
import com.elvdev.buoyient.testing.syncUpLocalChanges
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TodoServiceTest {

    @Test
    fun `create todo online returns server data`() = runBlocking {
        val env = TestServiceEnvironment()
        registerSyncDown(env)
        env.mockRouter.onPost("https://api.example.com/todos") { request ->
            MockResponse(
                statusCode = 201,
                body = buildJsonObject {
                    put("item", buildJsonObject {
                        put("server_id", "srv-1")
                        put("client_id", request.body["client_id"]!!)
                        put("version", "1")
                        put("title", request.body["title"]!!)
                        put("completed", request.body["completed"]!!)
                    })
                },
            )
        }

        val service = buildService(env)

        val result = service.createTodo(Todo(title = "Buy milk"))

        assertIs<SyncableObjectServiceResponse.Success.NetworkResponseReceived<Todo>>(result)
        assertEquals("srv-1", result.updatedData?.serverId)
        assertEquals("Buy milk", result.updatedData?.title)
        assertEquals(1, env.mockRouter.requestLog.size)

        service.close()
    }

    @Test
    fun `create todo offline queues locally then syncs online`() = runBlocking {
        val env = TestServiceEnvironment()
        registerSyncDown(env)
        env.connectivityChecker.online = false
        env.mockRouter.onPost("https://api.example.com/todos") { request ->
            MockResponse(
                statusCode = 201,
                body = buildJsonObject {
                    put("item", buildJsonObject {
                        put("server_id", "srv-2")
                        put("client_id", request.body["client_id"]!!)
                        put("version", "1")
                        put("title", request.body["title"]!!)
                        put("completed", request.body["completed"]!!)
                    })
                },
            )
        }

        val service = buildService(env)

        val offlineResult = service.createTodo(Todo(title = "Queue me"))

        assertIs<SyncableObjectServiceResponse.Success.StoredLocally<Todo>>(offlineResult)
        assertEquals(SyncableObject.SyncStatus.PendingCreate.status, offlineResult.updatedData.syncStatus.status)
        assertEquals(0, env.mockRouter.requestLog.size)

        env.connectivityChecker.online = true
        val syncedCount = service.syncUpLocalChanges()

        assertEquals(1, syncedCount)
        assertEquals(1, env.mockRouter.requestLog.size)
        assertEquals("Queue me", service.getAllFromLocalStore().single().title)

        service.close()
    }

    private suspend fun buildService(env: TestServiceEnvironment): TodoService {
        return TodoService(
            connectivityChecker = env.connectivityChecker,
        ).also {
            it.stopPeriodicSyncDown()
            // Allow the initial sync-down (launched on Dispatchers.IO in the SyncDriver
            // constructor) to settle before clearing the request log. Without this,
            // the background GET can land after clearRequestLog(), inflating
            // requestLog.size and failing assertions.
            delay(100)
            env.mockRouter.clearRequestLog()
        }
    }

    private fun registerSyncDown(env: TestServiceEnvironment) {
        env.mockRouter.onGet("https://api.example.com/todos") {
            MockResponse(
                statusCode = 200,
                body = buildJsonObject {
                    put("items", JsonArray(emptyList()))
                },
            )
        }
    }
}
