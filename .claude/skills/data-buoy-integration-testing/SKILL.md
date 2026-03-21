---
name: data-buoy-integration-testing
description: "How to write automated integration tests for SyncableObjectService implementations using data-buoy's testing module. Use this skill when the user wants to test a data-buoy service, write unit or integration tests for sync behavior, test online/offline paths, verify mock server responses, assert on pending request queues, or test sync-up/sync-down flows. Also trigger when the user mentions TestServiceEnvironment, MockEndpointRouter, MockResponse, or asks about testing offline-first sync logic."
---

# Writing Integration Tests for data-buoy Services

This skill covers how to write automated JVM integration tests for `SyncableObjectService` implementations using the `:testing` module. The module provides pre-built test doubles and a mock HTTP server so you don't need to manually wire Ktor `MockEngine`, `ServerManager`, `LocalStoreManager`, or anonymous stub implementations.

---

## Dependencies

Add the testing module as a `testImplementation` dependency in the consuming app's `build.gradle.kts`:

```kotlin
testImplementation(project(":testing"))
// or, if consuming as a published artifact:
testImplementation("com.les.databuoy:testing:<version>")
```

The `:testing` module transitively provides everything from `:library`, plus `ktor-client-mock` and an in-memory SQLite driver. No additional test dependencies are needed.

---

## Core Concepts

### TestServiceEnvironment

`TestServiceEnvironment` is the all-in-one harness. It bundles every dependency a `SyncableObjectService` needs with test-friendly defaults:

| Property | Type | Default | Purpose |
|----------|------|---------|---------|
| `mockRouter` | `MockEndpointRouter` | empty router | Register mock endpoint handlers |
| `connectivityChecker` | `TestConnectivityChecker` | `online = true` | Control online/offline state |
| `logger` | `SyncLogger` | `NoOpSyncLogger` (silent) | Swap to `PrintSyncLogger` for debugging |
| `syncScheduleNotifier` | `SyncScheduleNotifier` | `NoOpSyncScheduleNotifier` | No-op (no WorkManager in tests) |
| `idGenerator` | `IdGenerator` | `IncrementingIdGenerator` | Deterministic IDs: `test-id-1`, `test-id-2`, ... |
| `database` | `SyncDatabase` | in-memory SQLite | Isolated per `TestServiceEnvironment` instance |
| `serverManager` | `ServerManager` | built from `mockRouter` (lazy) | Pass to service constructor |

Each `TestServiceEnvironment` instance gets its own isolated in-memory database, so tests cannot interfere with each other.

### MockEndpointRouter

The mock HTTP server. Register handlers by HTTP method and URL pattern before exercising the service:

```kotlin
env.mockRouter.onPost("https://api.example.com/items") { request ->
    MockResponse(statusCode = 201, body = buildJsonObject { ... })
}
```

**URL pattern matching** (matched in registration order, first match wins):
- Exact: `"https://api.example.com/items"` -- matches only that URL
- Trailing wildcard: `"https://api.example.com/items/*"` -- matches any URL starting with prefix
- Leading wildcard: `"*/items"` -- matches any URL ending with suffix
- Both: `"*/items/*"` -- matches if URL contains the substring

**Request recording**: every request is captured in `env.mockRouter.requestLog` for assertions.

### MockResponse and RecordedRequest

```kotlin
// What the handler returns:
data class MockResponse(
    val statusCode: Int,
    val body: JsonObject,
    val epochTimestamp: Long = System.currentTimeMillis() / 1000,
)

// What the handler receives:
data class RecordedRequest(
    val method: HttpRequest.HttpMethod,
    val url: String,
    val body: JsonObject,
    val headers: Map<String, String>,
)
```

### MockConnectionException

Throw from a handler to simulate a network failure (translates to `ServerManagerResponse.ConnectionError`):

```kotlin
env.mockRouter.onPost("https://api.example.com/items") { throw MockConnectionException() }
```

---

## Test Structure Template

Every integration test follows the same pattern:

1. Create a `TestServiceEnvironment`
2. Register mock endpoint handlers
3. Construct the service under test, passing env dependencies
4. Stop periodic sync-down (if testing sync-up only)
5. Exercise the service
6. Assert on response, local DB state, and/or request log

```kotlin
import com.example.sync.testing.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YourModelServiceTest {

    @Test
    fun `create item online returns server response`() = runBlocking {
        // 1. Environment
        val env = TestServiceEnvironment()

        // 2. Mock endpoints
        env.mockRouter.onPost("https://api.example.com/v2/items") { request ->
            MockResponse(
                statusCode = 201,
                body = buildJsonObject {
                    put("item", buildJsonObject {
                        put("id", "srv-1")
                        put("reference_id", request.body["reference_id"]!!)
                        put("name", request.body["name"]!!)
                        put("version", 1)
                    })
                },
            )
        }
        // Sync-down endpoint (required since SyncDriver init triggers periodic sync)
        env.mockRouter.onGet("https://api.example.com/v2/items") { _ ->
            MockResponse(200, buildJsonObject { put("items", kotlinx.serialization.json.JsonArray(emptyList())) })
        }

        // 3. Construct service
        val service = YourModelService(
            serverProcessingConfig = YourModelServerProcessingConfig(),
            connectivityChecker = env.connectivityChecker,
            serverManager = env.serverManager,
            localStoreManager = env.createLocalStoreManager(
                codec = SyncCodec(YourModel.serializer()),
                serviceName = "your_model",
            ),
            idGenerator = env.idGenerator,
            logger = env.logger,
            syncScheduleNotifier = env.syncScheduleNotifier,
        )

        // 5. Exercise
        val result = service.createItem(YourModel(name = "Test", amount = 100))

        // 6. Assert
        assertTrue(result is CreateItemResponse.Success)
        assertEquals("srv-1", result.item.serverId)
        assertEquals(1, env.mockRouter.requestLog.size)
        assertEquals(HttpRequest.HttpMethod.POST, env.mockRouter.requestLog[0].method)

        service.close()
    }
}
```

---

## Testing the Online Path (create, update, void)

When `env.connectivityChecker.online` is `true` (the default), the service sends requests to the mock server immediately.

```kotlin
@Test
fun `update item online sends PUT and returns updated data`() = runBlocking {
    val env = TestServiceEnvironment()
    env.mockRouter.onPut("https://api.example.com/v2/items/*") { request ->
        MockResponse(200, buildJsonObject {
            put("item", buildJsonObject {
                put("id", "srv-1")
                put("reference_id", "c1")
                put("name", "Updated Name")
                put("version", 2)
            })
        })
    }
    // ... register sync-down endpoint, construct service ...

    val result = service.updateItem(existingItem, newName = "Updated Name")

    assertTrue(result is SyncableObjectServiceResponse.Finished.NetworkResponseReceived)
    assertEquals(200, result.statusCode)
}
```

---

## Testing the Offline Path (queuing and sync-up)

Set `online = false` to force the offline code path, then flip back to `true` and trigger sync-up manually.

```kotlin
@Test
fun `create item offline queues locally then syncs up`() = runBlocking {
    val env = TestServiceEnvironment()
    env.connectivityChecker.online = false  // Force offline

    // Mock for when sync-up eventually runs
    env.mockRouter.onPost("https://api.example.com/v2/items") { request ->
        MockResponse(201, buildJsonObject {
            put("item", buildJsonObject {
                put("id", "srv-1")
                put("reference_id", request.body["reference_id"]!!)
                put("name", request.body["name"]!!)
                put("version", 1)
            })
        })
    }

    val service = buildService(env) // helper that constructs the service with env deps

    // Create while offline -- should store locally
    val result = service.createItem(YourModel(name = "Offline Item", amount = 50))
    assertTrue(result is CreateItemResponse.Success)
    assertEquals(0, env.mockRouter.requestLog.size) // No HTTP request made

    // Now go online and sync
    env.connectivityChecker.online = true
    // Note: use SyncDriver.syncUpLocalChanges() directly if you have access,
    // or trigger via the service's sync mechanism
    val synced = service.syncUpLocalChanges()
    assertEquals(1, synced)
    assertEquals(1, env.mockRouter.requestLog.size) // Now the request was sent
}
```

---

## Testing Connection Errors

Simulate connection failures using `MockConnectionException`:

```kotlin
@Test
fun `create returns NoInternetConnection on connection error`() = runBlocking {
    val env = TestServiceEnvironment()
    env.mockRouter.onPost("https://api.example.com/v2/items") {
        throw MockConnectionException()
    }

    val service = buildService(env)
    val result = service.createItem(YourModel(name = "Test", amount = 100))

    assertTrue(result is SyncableObjectServiceResponse.NoInternetConnection)
}
```

---

## Testing Server Error Responses

Return non-2xx status codes from handlers:

```kotlin
@Test
fun `create handles 422 validation error`() = runBlocking {
    val env = TestServiceEnvironment()
    env.mockRouter.onPost("https://api.example.com/v2/items") { _ ->
        MockResponse(422, buildJsonObject {
            put("errors", buildJsonArray {
                add(buildJsonObject { put("detail", "Name is required") })
            })
        })
    }

    val service = buildService(env)
    val result = service.createItem(YourModel(name = "", amount = 100))

    assertTrue(result is CreateItemResponse.Failed)
}
```

---

## Testing Sync-Down (server-to-local)

Register a GET or POST handler for the sync-fetch endpoint, then call `syncDownFromServer()`:

```kotlin
@Test
fun `sync down upserts server items into local store`() = runBlocking {
    val env = TestServiceEnvironment()
    env.mockRouter.onGet("https://api.example.com/v2/items") { _ ->
        MockResponse(200, buildJsonObject {
            put("items", buildJsonArray {
                add(buildJsonObject {
                    put("id", "srv-1")
                    put("reference_id", "c1")
                    put("name", "Server Item")
                    put("version", 1)
                })
            })
        })
    }

    val service = buildService(env)
    service.syncDownFromServer()

    val localItems = service.getAllFromLocalStore()
    assertEquals(1, localItems.size)
    assertEquals("Server Item", localItems[0].name)
}
```

---

## Asserting on Request Details

Use `env.mockRouter.requestLog` to verify what was sent:

```kotlin
// Check request count
assertEquals(2, env.mockRouter.requestLog.size)

// Check specific request
val createRequest = env.mockRouter.requestLog[0]
assertEquals(HttpRequest.HttpMethod.POST, createRequest.method)
assertEquals("https://api.example.com/v2/items", createRequest.url)
assertTrue(createRequest.body.containsKey("idempotency_key"))
assertEquals("Test Item", createRequest.body["name"]?.jsonPrimitive?.content)

// Check headers
assertTrue(createRequest.headers.containsKey("Authorization"))

// Clear between test phases
env.mockRouter.clearRequestLog()
```

---

## Dynamic / Stateful Mock Responses

For tests where the server response depends on request order (e.g., CREATE then UPDATE):

```kotlin
val responseQueue = ArrayDeque(listOf(createResponseJson, updateResponseJson))
env.mockRouter.onPost("https://api.example.com/v2/items") { _ ->
    MockResponse(200, responseQueue.removeFirst())
}
```

Or use a counter:

```kotlin
var requestCount = 0
env.mockRouter.onPost("https://api.example.com/v2/items") { _ ->
    requestCount++
    val body = if (requestCount == 1) createResponseBody else updateResponseBody
    MockResponse(200, body)
}
```

---

## Deterministic ID Generation

`IncrementingIdGenerator` produces IDs in sequence: `test-id-1`, `test-id-2`, etc. This makes assertions on idempotency keys predictable:

```kotlin
val env = TestServiceEnvironment(
    idGenerator = IncrementingIdGenerator(prefix = "idem"),
)
// First create() will use idempotency key "idem-1"
// Second create() will use "idem-2"
```

Call `(env.idGenerator as IncrementingIdGenerator).reset()` between test phases if needed.

---

## Debugging Failing Tests

Swap `NoOpSyncLogger` for `PrintSyncLogger` to see all internal sync engine logs:

```kotlin
val env = TestServiceEnvironment(logger = PrintSyncLogger)
```

This prints every HTTP request/response, sync status transition, and merge operation to stdout.

---

## Helper Pattern: buildService()

To reduce repetition, define a helper that constructs the service from a `TestServiceEnvironment`:

```kotlin
private fun buildService(env: TestServiceEnvironment): YourModelService {
    return YourModelService(
        serverProcessingConfig = YourModelServerProcessingConfig(),
        connectivityChecker = env.connectivityChecker,
        serverManager = env.serverManager,
        localStoreManager = env.createLocalStoreManager(
            codec = SyncCodec(YourModel.serializer()),
            serviceName = "your_model",
        ),
        idGenerator = env.idGenerator,
        logger = env.logger,
        syncScheduleNotifier = env.syncScheduleNotifier,
    )
}
```

---

## Important Rules

- **One `TestServiceEnvironment` per test.** Each instance has its own in-memory database, so tests are isolated by default.
- **Register sync-down endpoint handlers** even if you're only testing create/update/void. The `SyncDriver` init starts periodic sync-down immediately, which will hit the sync-fetch endpoint. If you don't register a handler, it will get a 404 (which is usually harmless but can produce confusing log output). Alternatively, set `syncCadenceSeconds` to a very large value in your test config.
- **Call `service.close()`** at the end of each test to cancel the periodic sync-down coroutine and release resources.
- **Stop periodic sync-down** if you only want to test sync-up in isolation. After constructing the service, call `service.stopPeriodicSyncDown()` (inherited from `SyncDriver`).
- **The `serverManager` is lazy.** Handlers registered on `env.mockRouter` after `TestServiceEnvironment` construction are still picked up because the `MockEngine` evaluates handlers at request time, not at build time.

---

## Available Testing Utilities Reference

All classes are in the `com.example.sync.testing` package:

| Class | Purpose |
|-------|---------|
| `TestServiceEnvironment` | All-in-one harness bundling all dependencies |
| `MockEndpointRouter` | Register mock HTTP endpoint handlers, inspect request log |
| `MockRequestHandler` | Functional interface: `(RecordedRequest) -> MockResponse` |
| `RecordedRequest` | Captured request: method, url, body, headers |
| `MockResponse` | Mock response: statusCode, body, epochTimestamp |
| `MockConnectionException` | Throw to simulate network failure |
| `TestConnectivityChecker` | Mutable `online` property to control connectivity |
| `TestDatabaseFactory` | `createInMemory()` for isolated in-memory databases |
| `IncrementingIdGenerator` | Deterministic sequential IDs |
| `NoOpSyncLogger` | Silent logger |
| `PrintSyncLogger` | Stdout logger for debugging |
| `NoOpSyncScheduleNotifier` | No-op notifier (no WorkManager in tests) |
