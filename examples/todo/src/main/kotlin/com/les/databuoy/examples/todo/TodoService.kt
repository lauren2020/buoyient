package com.les.databuoy.examples.todo

import com.les.databuoy.serviceconfigs.ConnectivityChecker
import com.les.databuoy.HttpRequest
import com.les.databuoy.serviceconfigs.ServerProcessingConfig
import com.les.databuoy.SyncableObjectService
import com.les.databuoy.syncableobjectservicedatatypes.SyncableObjectServiceResponse
import com.les.databuoy.syncableobjectservicedatatypes.UpdateRequestBuilder
import com.les.databuoy.syncableobjectservicedatatypes.VoidRequestBuilder
import com.les.databuoy.syncableobjectservicedatatypes.CreateRequestBuilder
import com.les.databuoy.serviceconfigs.createPlatformConnectivityChecker
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
