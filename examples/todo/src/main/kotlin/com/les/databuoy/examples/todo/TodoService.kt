package com.les.databuoy.examples.todo

import com.les.databuoy.ConnectivityChecker
import com.les.databuoy.HttpRequest
import com.les.databuoy.IdGenerator
import com.les.databuoy.LocalStoreManager
import com.les.databuoy.ResponseUnpacker
import com.les.databuoy.ServerManager
import com.les.databuoy.ServerProcessingConfig
import com.les.databuoy.SyncCodec
import com.les.databuoy.SyncLogger
import com.les.databuoy.SyncScheduleNotifier
import com.les.databuoy.SyncableObjectService
import com.les.databuoy.SyncableObjectServiceResponse
import com.les.databuoy.UpdateRequestBuilder
import com.les.databuoy.VoidRequestBuilder
import com.les.databuoy.CreateRequestBuilder
import com.les.databuoy.createPlatformConnectivityChecker
import com.les.databuoy.createPlatformIdGenerator
import com.les.databuoy.createPlatformSyncLogger
import com.les.databuoy.createPlatformSyncScheduleNotifier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class TodoService(
    serverProcessingConfig: ServerProcessingConfig<Todo> = TodoServerProcessingConfig(),
    logger: SyncLogger = createPlatformSyncLogger(),
    serverManager: ServerManager = ServerManager(
        serviceBaseHeaders = serverProcessingConfig.globalHeaders,
        logger = logger,
    ),
) : SyncableObjectService<Todo, TodoRequestTag>(
    serializer = Todo.serializer(),
    serverProcessingConfig = serverProcessingConfig,
    serviceName = SERVICE_NAME,
    logger = logger,
    serverManager = serverManager,
) {

    private val json = Json { ignoreUnknownKeys = true }

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
        unpackData = ResponseUnpacker { responseBody, statusCode, syncStatus ->
            serverProcessingConfig.unpackCreateTodoResponse(responseBody).data
        },
    )

    suspend fun editTodo(todo: Todo): SyncableObjectServiceResponse<Todo> = update(
        data = todo,
        requestTag = TodoRequestTag.EDIT_TODO,
        request = UpdateRequestBuilder { _, updatedData, idempotencyKey, _, _ ->
            HttpRequest(
                method = HttpRequest.HttpMethod.PATCH,
                endpointUrl = "$BASE_ENDPOINT/${updatedData.serverId ?: HttpRequest.SERVER_ID_PLACEHOLDER}/edit",
                requestBody = buildJsonObject {
                    put("title", updatedData.title)
                    put("completed", updatedData.completed)
                    put("version", updatedData.version)
                    put("idempotency_key", idempotencyKey)
                },
            )
        },
        unpackData = ResponseUnpacker { responseBody, statusCode, syncStatus ->
            serverProcessingConfig.unpackEditTodoResponse(responseBody).data
        },
    )

    suspend fun completeTodo(todo: Todo): SyncableObjectServiceResponse<Todo> = update(
        data = todo,
        requestTag = TodoRequestTag.COMPLETE_TODO,
        request = UpdateRequestBuilder { _, updatedData, idempotencyKey, _, _ ->
            HttpRequest(
                method = HttpRequest.HttpMethod.PATCH,
                endpointUrl = "$BASE_ENDPOINT/${updatedData.serverId ?: HttpRequest.SERVER_ID_PLACEHOLDER}/complete",
                requestBody = buildJsonObject {
                    put("title", updatedData.title)
                    put("completed", updatedData.completed)
                    put("version", updatedData.version)
                    put("idempotency_key", idempotencyKey)
                },
            )
        },
        unpackData = ResponseUnpacker { responseBody, statusCode, syncStatus ->
            serverProcessingConfig.unpackCompleteTodoResponse(responseBody).data
        },
    )

    suspend fun removeTodo(todo: Todo): SyncableObjectServiceResponse<Todo> = void(
        data = todo,
        requestTag = TodoRequestTag.REMOVE_TODO,
        request = VoidRequestBuilder { data, _ ->
            HttpRequest(
                method = HttpRequest.HttpMethod.DELETE,
                endpointUrl = "$BASE_ENDPOINT/${data.serverId ?: HttpRequest.SERVER_ID_PLACEHOLDER}",
                requestBody = buildJsonObject { },
            )
        },
        unpackData = ResponseUnpacker { responseBody, statusCode, syncStatus ->
            serverProcessingConfig.unpackRemoveTodoResponse(responseBody).data
        },
    )

    private companion object {
        const val SERVICE_NAME = "todo_example"
        const val BASE_ENDPOINT = "https://api.example.com/todos"
    }
}
