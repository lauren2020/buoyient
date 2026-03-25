package com.les.databuoy.examples.todo

import com.les.databuoy.ServerProcessingConfig
import com.les.databuoy.SyncFetchConfig
import com.les.databuoy.SyncUpConfig
import com.les.databuoy.SyncUpResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class TodoServerProcessingConfig : ServerProcessingConfig<Todo> {

    private val json = Json { ignoreUnknownKeys = true }

    override val syncFetchConfig = SyncFetchConfig.GetFetchConfig(
        endpoint = "https://api.example.com/todos",
        syncCadenceSeconds = 300,
        transformResponse = { response ->
            val items = response["items"]?.jsonArray ?: return@GetFetchConfig emptyList()
            items.map { json.decodeFromJsonElement(Todo.serializer(), it.jsonObject) }
        },
    )

    override val syncUpConfig = object : SyncUpConfig<Todo>() {
        override fun fromResponseBody(requestTag: String, responseBody: JsonObject): SyncUpResult<Todo> {
            return when (requestTag) {
                TodoRequestTag.CREATE_TODO.value -> unpackCreateTodoResponse(responseBody)
                TodoRequestTag.UPDATE_TODO.value -> unpackEditTodoResponse(responseBody)
                TodoRequestTag.COMPLETE_TODO.value -> unpackCompleteTodoResponse(responseBody)
                TodoRequestTag.VOID_TODO.value -> unpackRemoveTodoResponse(responseBody)
                else -> SyncUpResult.Failed.RemovePendingRequest()
            }
        }
    }

    override val serviceHeaders: List<Pair<String, String>> = listOf(
        "Content-Type" to "application/json",
    )

    fun unpackCreateTodoResponse(responseBody: JsonObject): SyncUpResult<Todo> {
        val item = responseBody["item"]?.jsonObject ?: return SyncUpResult.Failed.RemovePendingRequest()
        return SyncUpResult.Success(json.decodeFromJsonElement(Todo.serializer(), item))
    }

    fun unpackEditTodoResponse(responseBody: JsonObject): SyncUpResult<Todo> {
        val item = responseBody["item"]?.jsonObject ?: return SyncUpResult.Failed.RemovePendingRequest()
        return SyncUpResult.Success(json.decodeFromJsonElement(Todo.serializer(), item))
    }

    fun unpackCompleteTodoResponse(responseBody: JsonObject): SyncUpResult<Todo> {
        val item = responseBody["item"]?.jsonObject ?: return SyncUpResult.Failed.RemovePendingRequest()
        return SyncUpResult.Success(json.decodeFromJsonElement(Todo.serializer(), item))
    }

    fun unpackRemoveTodoResponse(responseBody: JsonObject): SyncUpResult<Todo> {
        val item = responseBody["item"]?.jsonObject ?: return SyncUpResult.Failed.RemovePendingRequest()
        return SyncUpResult.Success(json.decodeFromJsonElement(Todo.serializer(), item))
    }
}
