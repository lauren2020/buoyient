package com.elvdev.buoyient.examples.todo

import com.elvdev.buoyient.serviceconfigs.ConnectivityChecker
import com.elvdev.buoyient.datatypes.HttpRequest
import com.elvdev.buoyient.serviceconfigs.ServerProcessingConfig
import com.elvdev.buoyient.SyncableObjectService
import com.elvdev.buoyient.datatypes.SyncableObjectServiceResponse
import com.elvdev.buoyient.datatypes.UpdateRequestBuilder
import com.elvdev.buoyient.datatypes.VoidRequestBuilder
import com.elvdev.buoyient.datatypes.CreateRequestBuilder
import com.elvdev.buoyient.serviceconfigs.createPlatformConnectivityChecker
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class TodoService(
    serverProcessingConfig: ServerProcessingConfig<Todo> = createTodoServerProcessingConfig(),
    connectivityChecker: ConnectivityChecker = createPlatformConnectivityChecker(),
) : SyncableObjectService<Todo, TodoRequestTag>(
    serializer = Todo.serializer(),
    serverProcessingConfig = serverProcessingConfig,
    serviceName = SERVICE_NAME,
    connectivityChecker = connectivityChecker,
) {

    suspend fun createTodo(todo: Todo): SyncableObjectServiceResponse<Todo> = create(
        data = todo,
        requestTag = TodoRequestTag.CREATE_TODO,
        request = CreateRequestBuilder { data, idempotencyKey, _, _ ->
            HttpRequest(
                method = HttpRequest.HttpMethod.POST,
                endpointUrl = BASE_ENDPOINT,
                requestBody = buildJsonObject {
                    put("client_id", data.clientId)
                    put("title", data.title)
                    put("completed", data.completed)
                    put("idempotency_key", idempotencyKey)
                },
            )
        },
        unpackSyncData = todoResponseUnpacker,
    )

    suspend fun editTodo(todo: Todo): SyncableObjectServiceResponse<Todo> = update(
        data = todo,
        requestTag = TodoRequestTag.UPDATE_TODO,
        request = UpdateRequestBuilder { _, updatedData, idempotencyKey, _, _ ->
            HttpRequest(
                method = HttpRequest.HttpMethod.PATCH,
                endpointUrl = "$BASE_ENDPOINT/${HttpRequest.serverIdOrPlaceholder(updatedData.serverId)}/edit",
                requestBody = buildJsonObject {
                    put("title", updatedData.title)
                    put("completed", updatedData.completed)
                    put("version", updatedData.version)
                    put("idempotency_key", idempotencyKey)
                },
            )
        },
        unpackSyncData = todoResponseUnpacker,
    )

    suspend fun completeTodo(todo: Todo): SyncableObjectServiceResponse<Todo> = update(
        data = todo,
        requestTag = TodoRequestTag.COMPLETE_TODO,
        request = UpdateRequestBuilder { _, updatedData, idempotencyKey, _, _ ->
            HttpRequest(
                method = HttpRequest.HttpMethod.PATCH,
                endpointUrl = "$BASE_ENDPOINT/${HttpRequest.serverIdOrPlaceholder(updatedData.serverId)}/complete",
                requestBody = buildJsonObject {
                    put("title", updatedData.title)
                    put("completed", updatedData.completed)
                    put("version", updatedData.version)
                    put("idempotency_key", idempotencyKey)
                },
            )
        },
        unpackSyncData = todoResponseUnpacker,
    )

    suspend fun removeTodo(todo: Todo): SyncableObjectServiceResponse<Todo> = void(
        data = todo,
        requestTag = TodoRequestTag.VOID_TODO,
        request = VoidRequestBuilder { data, _, _ ->
            HttpRequest(
                method = HttpRequest.HttpMethod.DELETE,
                endpointUrl = "$BASE_ENDPOINT/${HttpRequest.serverIdOrPlaceholder(data.serverId)}",
                requestBody = buildJsonObject { },
            )
        },
        unpackData = todoResponseUnpacker,
    )

    private companion object {
        const val SERVICE_NAME = "todo_example"
        const val BASE_ENDPOINT = "https://api.example.com/todos"
    }
}
