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
- **3-Way Merge & Conflict Resolution** — Field-level conflict detection using base/local/server comparison. Pluggable merge policies via `SyncableObjectRebaseHandler`.
- **Pending Request Queue** — Queued requests are persisted to SQLite, survive app restarts, and support idempotency keys for safe retries.
- **Placeholder Resolution** — Use `{serverId}` and `{version}` placeholders in endpoint URLs and request bodies. These are resolved at sync time with the most up-to-date values, enabling safe chaining of CREATE -> UPDATE -> VOID operations.
- **Request Tagging** — `ServiceRequestTag` enums for tracking request types and custom response handling per operation.

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
ServerManager  LocalStoreManager  SyncableObjectRebaseHandler
(Ktor HTTP)   (SQLDelight)        (3-way merge)
               │
               ▼
        PendingRequestQueueManager
        (offline request queue)
```

## Usage

### 1. Define your data model

Implement `SyncableObject<O>` on a `@Serializable` data class:

```kotlin
@Serializable
data class Todo(
    override val serverId: String? = null,
    override val clientId: String = UUID.randomUUID().toString(),
    override val version: Int = 0,
    @Transient override val syncStatus: SyncStatus = SyncStatus.LocalOnly,
    val title: String,
    val completed: Boolean = false,
) : SyncableObject<Todo> {
    override fun withSyncStatus(syncStatus: SyncStatus): Todo =
        copy(syncStatus = syncStatus)
}
```

### 2. Define a request tag

```kotlin
enum class TodoRequestTag(override val value: String) : ServiceRequestTag {
    CREATE("create"),
    UPDATE("update"),
    VOID("void"),
}
```

### 3. Configure server processing

Implement `ServerProcessingConfig<O>` to define how your service communicates with the server — fetch endpoint, upload success criteria, HTTP headers, and response deserialization.

### 4. Implement your service

Extend `SyncableObjectService<O, T>` and expose domain-specific operations using the protected `create()`, `update()`, `void()`, and `get()` methods:

```kotlin
class TodoService(
    serverProcessingConfig: ServerProcessingConfig<Todo> = TodoServerProcessingConfig(),
    // ... other constructor params with defaults (see docs/creating-a-service.md)
) : SyncableObjectService<Todo, TodoRequestTag>(
    serializer = Todo.serializer(),
    serverProcessingConfig = serverProcessingConfig,
    serviceName = "todos",
    // ... pass through other params
) {
    suspend fun addTodo(title: String): SyncableObjectServiceResponse<Todo> {
        val todo = Todo(title = title)
        return create(
            data = todo,
            requestTag = TodoRequestTag.CREATE,
            request = CreateRequestBuilder { data, idempotencyKey, isOffline, attemptedServerRequest ->
                HttpRequest(
                    method = HttpRequest.HttpMethod.POST,
                    endpointUrl = "https://api.example.com/todos",
                    requestBody = buildJsonObject {
                        put("idempotency_key", idempotencyKey)
                        put("title", data.title)
                        put("reference_id", data.clientId)
                    },
                )
            },
            unpackSyncData = ResponseUnpacker { responseBody, statusCode, syncStatus ->
                if (statusCode in 200..299 && responseBody.containsKey("item")) {
                    json.decodeFromJsonElement(Todo.serializer(), responseBody["item"]!!.jsonObject)
                        .withSyncStatus(syncStatus)
                } else null
            },
        )
    }
}
```

### 5. Register services for background sync

The sync engine needs to know which services to sync when the background worker fires. Choose the approach that fits your app:

#### With Hilt (recommended)

Add the `data-buoy-hilt` dependency and provide services via standard `@IntoSet` multibindings. No `Application.onCreate()` override needed — registration is fully automatic.

```kotlin
// build.gradle.kts
implementation("com.les.databuoy:library:<version>")
implementation("com.les.databuoy:data-buoy-hilt:<version>")
```

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object SyncModule {
    @Provides @IntoSet
    fun todoService(): SyncableObjectService<*, *> = TodoService()
}
```

#### Without Hilt

Use the `DataBuoy` convenience API in `Application.onCreate()`:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DataBuoy.registerServices(setOf(TodoService()))
    }
}
```

Or register a factory that creates fresh service instances per sync pass:

```kotlin
DataBuoy.registerServiceProvider(object : SyncServiceRegistryProvider {
    override fun createServices(context: Context) = listOf(TodoService())
})
```

## Key Extension Points

| Class | Override | Purpose |
|-------|----------|---------|
| `SyncableObjectService` | `create()`, `update()`, `void()`, `get()` | Define your public API |
| `SyncableObjectRebaseHandler` | `rebaseDataForPendingRequest()` | Custom 3-way merge logic |
| `SyncableObjectRebaseHandler` | `handleMergeConflict()` | Custom conflict resolution |
| `SyncUpConfig` | `acceptUploadResponseAsProcessed()` | Custom success criteria |
| `SyncUpConfig` | `fromResponseBody()` | Response deserialization for sync-up (returns `SyncUpResult`) |

## Testing

The `:testing` module provides utilities for both automated integration tests and runtime mock mode, so you can test your services without a real backend.

```kotlin
// build.gradle.kts
testImplementation("com.les.databuoy:testing:<version>")
```

### Integration Tests

Use `TestServiceEnvironment` to get a fully wired test harness with an in-memory database, mock HTTP server, and controllable connectivity:

```kotlin
@Test
fun `create todo online returns server response`() = runBlocking {
    val env = TestServiceEnvironment()

    env.mockRouter.onPost("https://api.example.com/todos") { request ->
        MockResponse(201, buildJsonObject {
            put("item", buildJsonObject {
                put("id", "srv-1")
                put("reference_id", request.body["reference_id"]!!)
                put("title", request.body["title"]!!)
                put("version", 1)
            })
        })
    }

    val service = TodoService(
        serverProcessingConfig = TodoServerProcessingConfig(),
        connectivityChecker = env.connectivityChecker,
        serverManager = env.serverManager,
        localStoreManager = env.createLocalStoreManager(
            codec = SyncCodec(Todo.serializer()),
            serviceName = "todos",
        ),
        idGenerator = env.idGenerator,
        logger = env.logger,
        syncScheduleNotifier = env.syncScheduleNotifier,
    )

    val result = service.addTodo("Buy milk")
    assertTrue(result is SyncableObjectServiceResponse.Finished.NetworkResponseReceived)
    assertEquals(1, env.mockRouter.requestLog.size)

    service.close()
}
```

Test offline behavior by flipping connectivity mid-test:

```kotlin
env.connectivityChecker.online = false
// Operations now queue locally instead of hitting the server
```

### Mock Mode (Manual Testing)

Add the testing module as a regular dependency (scoped to debug builds) to run the full app against fake data:

```kotlin
// build.gradle.kts
debugImplementation("com.les.databuoy:testing:<version>")
```

Wire a `MockEndpointRouter` into your DI graph behind a developer toggle:

```kotlin
fun provideServerManager(useMock: Boolean): ServerManager {
    if (useMock) {
        val router = MockEndpointRouter()
        router.onGet("https://api.example.com/todos") { _ ->
            MockResponse(200, loadFixture("todos.json"))
        }
        router.onPost("https://api.example.com/todos") { request ->
            MockResponse(201, buildJsonObject { put("item", request.body) })
        }
        return router.buildServerManager()
    }
    return ServerManager(headers, logger)
}
```

### Testing Utilities

| Class | Purpose |
|-------|---------|
| `TestServiceEnvironment` | All-in-one harness bundling mock server, in-memory DB, and test doubles |
| `MockEndpointRouter` | Register mock HTTP handlers by method + URL pattern; inspect request log |
| `MockResponse` / `RecordedRequest` | Define responses and inspect captured requests |
| `MockConnectionException` | Throw from a handler to simulate network failure |
| `MockServerStore` | Stateful mock server — manages named collections of server-side records |
| `MockServerCollection` | Per-collection CRUD, seed, mutate, and inspect server-side data |
| `MockServerRecord` | A single server-side record with serverId, version, data, and timestamps |
| `registerCrudHandlers()` | Extension on `MockEndpointRouter` — auto-wires CRUD handlers backed by a collection |
| `registerSyncDownHandler()` | Extension on `MockEndpointRouter` — auto-wires sync-down with timestamp filtering |
| `TestConnectivityChecker` | Mutable `online` flag to control online/offline paths |
| `TestDatabaseFactory` | Create isolated in-memory SQLite databases |
| `IncrementingIdGenerator` | Deterministic sequential IDs for predictable assertions |
| `NoOpSyncLogger` / `PrintSyncLogger` | Silent or stdout logging |

## Modules

| Module | Artifact | Purpose |
|--------|----------|---------|
| `:library` | `com.les.databuoy:library` | Core sync engine (KMP) |
| `:hilt` | `com.les.databuoy:data-buoy-hilt` | Optional Hilt integration — auto-registers services via `@IntoSet` multibinding |
| `:testing` | `com.les.databuoy:testing` | Test utilities — mock server, in-memory DB, test doubles |

## Detailed Guides

The `docs/` directory contains step-by-step guides for integrating data-buoy into a consuming application:

| Guide | Description |
|-------|-------------|
| [Setup](docs/setup.md) | Adding data-buoy to your app — dependencies, initialization, and service registration |
| [Creating a Service](docs/creating-a-service.md) | Data model, `ServerProcessingConfig`, service class, and registration |
| [Integration Testing](docs/integration-testing.md) | Automated JVM tests with `TestServiceEnvironment` and mock server |
| [Mock Mode](docs/mock-mode.md) | Runtime mock mode for manual testing without a real backend |

**For AI agents (Claude Code, Cursor, etc.):** This repo includes a `CLAUDE.md` and `.claude/skills/` with detailed instructions for setup, service creation, testing, and mock mode. These are loaded automatically by Claude Code when working in this project.

## Build

```bash
./gradlew :library:build
./gradlew :hilt:build
./gradlew :testing:build
```

To run tests:

```bash
./gradlew :testing:test
```

To publish to local Maven:

```bash
./gradlew :library:publishToMavenLocal
./gradlew :hilt:publishToMavenLocal
./gradlew :testing:publishToMavenLocal
```
