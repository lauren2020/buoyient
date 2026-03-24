---
name: data-buoy-integration-testing
description: "How to write automated integration tests for SyncableObjectService implementations using data-buoy's testing module. Use this skill when the user wants to test a data-buoy service, write unit or integration tests for sync behavior, test online/offline paths, verify mock server responses, assert on pending request queues, or test sync-up/sync-down flows. Also trigger when the user mentions TestServiceEnvironment, MockEndpointRouter, MockResponse, or asks about testing offline-first sync logic."
---

# Writing Integration Tests for data-buoy Services

This skill covers how to write automated JVM integration tests for `SyncableObjectService` implementations using the `:testing` module. The module provides pre-built test doubles and a mock HTTP server so you do not need to manually wire Ktor `MockEngine`, `ServerManager`, `LocalStoreManager`, or anonymous stub implementations.

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
- Exact: `"https://api.example.com/items"` - matches only that URL
- Trailing wildcard: `"https://api.example.com/items/*"` - matches any URL starting with prefix
- Leading wildcard: `"*/items"` - matches any URL ending with suffix
- Both: `"*/items/*"` - matches if URL contains the substring

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
4. Stop periodic sync-down if testing sync-up only
5. Exercise the service
6. Assert on response, local DB state, and/or request log

```kotlin
import com.les.databuoy.testing.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YourModelServiceTest {

    @Test
    fun `create item online returns server response`() = runBlocking {
        val env = TestServiceEnvironment()

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
        env.mockRouter.onGet("https://api.example.com/v2/items") { _ ->
            MockResponse(200, buildJsonObject { put("items", kotlinx.serialization.json.JsonArray(emptyList())) })
        }

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

        val result = service.createItem(YourModel(name = "Test", amount = 100))

        assertTrue(result is CreateItemResponse.Success)
        assertEquals("srv-1", result.item.serverId)
        assertEquals(1, env.mockRouter.requestLog.size)
        assertEquals(HttpRequest.HttpMethod.POST, env.mockRouter.requestLog[0].method)

        service.close()
    }
}
```

---

## Testing the Online Path

When `env.connectivityChecker.online` is `true` (the default), the service sends requests to the mock server immediately.

---

## Testing the Offline Path

Set `online = false` to force the offline code path, then flip back to `true` and trigger sync-up manually.

---

## Testing Connection Errors

Simulate connection failures using `MockConnectionException`.

---

## Testing Server Error Responses

Return non-2xx status codes from handlers.
