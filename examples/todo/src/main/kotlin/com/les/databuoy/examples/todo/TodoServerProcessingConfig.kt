package com.les.databuoy.examples.todo

import com.les.databuoy.ResponseUnpacker
import com.les.databuoy.ServerProcessingConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Example [ServerProcessingConfig] using the builder API.
 *
 * Because every endpoint returns `{ "item": { ... } }`, we define a single
 * [ResponseUnpacker.fromKey] and reuse it for both the synchronous unpacker
 * (in TodoService) and the async sync-up config (via [syncUpFromUnpacker]).
 */
fun createTodoServerProcessingConfig(): ServerProcessingConfig<Todo> {
    val json = Json { ignoreUnknownKeys = true }
    val unpacker = ResponseUnpacker.fromKey("item", Todo.serializer())

    return ServerProcessingConfig.builder<Todo>()
        .fetchWithGet(
            endpoint = "https://api.example.com/todos",
            syncCadenceSeconds = 300,
        ) { response ->
            val items = response["items"]?.jsonArray ?: return@fetchWithGet emptyList()
            items.map { json.decodeFromJsonElement(Todo.serializer(), it.jsonObject) }
        }
        .syncUpFromUnpacker(unpacker)
        .serviceHeaders("Content-Type" to "application/json")
        .build()
}

/** Shared unpacker for all Todo operations — extracts from the `"item"` key. */
val todoResponseUnpacker: ResponseUnpacker<Todo> = ResponseUnpacker.fromKey("item", Todo.serializer())
