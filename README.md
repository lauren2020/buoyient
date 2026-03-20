<p align="center">
  <img src="assets/icon.svg" alt="data-buoy icon" width="120"/>
</p>

# data-buoy
Keep data floating even when network conditions are rough.

> 🚧 **Under Construction** 🚧
>
> This library is still being actively developed and is not yet ready for production use.
> APIs may change, features may be incomplete, and here be dragons. Contributions and
> feedback are welcome!

## What
A Kotlin Multiplatform offline-first sync library that handles bidirectional data synchronization between local storage and a remote server, with built-in support for offline queuing, conflict resolution, and automatic retries.

## Features

- **Offline-First** — Create, update, and void data locally when offline. Changes are automatically queued and synced when connectivity returns.
- **Bidirectional Sync** — Periodic sync-down (server to local) and on-demand sync-up (local to server).
- **3-Way Merge & Conflict Resolution** — Field-level conflict detection using base/local/server comparison. Pluggable merge policies via `SyncableObjectMergeHandler`.
- **Pending Request Queue** — Queued requests are persisted to SQLite, survive app restarts, and support idempotency keys for safe retries.
- **Placeholder Resolution** — Use `{serverId}` and `{version}` placeholders in endpoint URLs and request bodies. These are resolved at sync time with the most up-to-date values, enabling safe chaining of CREATE -> UPDATE -> VOID operations.
- **Request Tagging** — Optional tags for tracking and custom response handling.

## Features in progress

- **LAN support** — even when internet connection is not available, devices on the same local network can still communicate with each other. This will add support for keeping data in sync across local devices on the same network even when server connection is not available.
- **StreamService** — the current SyncableObjectService focus on management of discrete data objects, StreamService will be a sibling offering that focus on data streams rather than discrete objects.

## Platform Support

| Platform | Status |
|----------|--------|
| Android  | Supported (API 27+) |
| iOS      | In progress |

### Android

- Ktor OkHttp client for networking
- SQLDelight Android driver for local storage
- WorkManager for background sync scheduling
- ConnectivityManager for online detection
- Auto-initialization via `androidx.startup`

### iOS

- Ktor Darwin client for networking
- SQLDelight Native driver for local storage
- Connectivity and background sync scheduling are stubbed (TODO)

## Architecture

```
┌─────────────────────────────────┐
│   YourService                   │  ← You implement this
│   extends SyncableObjectService │
└──────────────┬──────────────────┘
               │
       ┌───────▼────────┐
       │   SyncDriver    │  Orchestrates sync-up & sync-down
       └───────┬─────────┘
               │
    ┌──────────┼──────────────┐
    │          │              │
    ▼          ▼              ▼
ServerManager  LocalStoreManager  SyncableObjectMergeHandler
(Ktor HTTP)   (SQLDelight)        (3-way merge)
               │
               ▼
        PendingRequestQueueManager
        (offline request queue)
```

## Usage

### 1. Define your data model

Implement `SyncableObject<O>` on your domain object:

```kotlin
data class Todo(
    override val serverId: String?,
    override val clientId: String,
    override val version: Int,
    override val syncStatus: SyncStatus,
    val title: String,
    val completed: Boolean,
) : SyncableObject<Todo> {
    override fun toJson(): JsonObject = buildJsonObject {
        put("title", title)
        put("completed", completed)
        // ... include serverId, clientId, version, syncStatus
    }
}
```

### 2. Create a deserializer

```kotlin
class TodoDeserializer : SyncableObject.SyncableObjectDeserializer<Todo> {
    override fun fromJson(json: JsonObject, syncStatus: SyncStatus): Todo {
        return Todo(
            serverId = json["server_id"]?.jsonPrimitive?.contentOrNull,
            clientId = json["client_id"]!!.jsonPrimitive.content,
            version = json["version"]!!.jsonPrimitive.int,
            syncStatus = syncStatus,
            title = json["title"]!!.jsonPrimitive.content,
            completed = json["completed"]!!.jsonPrimitive.boolean,
        )
    }
}
```

### 3. Configure server processing

Implement `ServerProcessingConfig<O>` to define how your service communicates with the server — fetch endpoint, upload success criteria, HTTP headers, and response deserialization.

### 4. Implement your service

Extend `SyncableObjectService<O>` and expose domain-specific operations using the protected `create()`, `update()`, and `void()` methods:

```kotlin
class TodoService : SyncableObjectService<Todo>(
    deserializer = TodoDeserializer(),
    serverProcessingConfig = TodoServerProcessingConfig(),
    serviceName = "todos",
) {
    suspend fun addTodo(title: String): SyncableObjectServiceResponse<Todo> {
        val todo = Todo(
            serverId = null,
            clientId = "", // generated internally
            version = 0,
            syncStatus = SyncStatus.LocalOnly,
            title = title,
            completed = false,
        )
        return create(
            data = todo,
            request = { data, idempotencyKey, isOffline -> /* build HttpRequest */ },
            unpackSyncData = { status, response -> /* parse response */ },
        )
    }
}
```

### 5. Android setup

Register your services with the sync worker so background sync can find them:

```kotlin
class MyServiceRegistry : SyncServiceRegistryProvider {
    override fun getServices(): List<Service<*>> = listOf(TodoService())
}

// In Application.onCreate():
SyncWorker.registerServiceProvider(MyServiceRegistry())
```

## Key Extension Points

| Class | Override | Purpose |
|-------|----------|---------|
| `SyncableObjectService` | `create()`, `update()`, `void()` | Define your public API |
| `SyncableObjectMergeHandler` | `mergeServerAndLocalChanges()` | Custom 3-way merge logic |
| `SyncableObjectMergeHandler` | `handleMergeConflict()` | Custom conflict resolution |
| `SyncUpConfig` | `acceptUploadResponseAsProcessed()` | Custom success criteria |
| `ServerProcessingConfig` | `fromServerProtoJson()` | Response deserialization |

## Build

```bash
./gradlew :library:build
```

To publish to local Maven:

```bash
./gradlew :library:publishToMavenLocal
```
