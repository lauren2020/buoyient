<p align="center">
  <img src="assets/icon.svg" alt="buoyient icon" width="120"/>
</p>

# buoyient
keep your client up even when the network is down

Offline-first Kotlin Multiplatform SDK that keeps a local data store in sync with a remote server. App code performs data operations through a single API regardless of connectivity — changes persist locally immediately, queue for server transport when offline, and reconcile automatically when connectivity returns. Includes conflict resolution, automatic retries, and periodic sync-down from the server.

## For Humans: start here
This README is optimized for AI agents. For humans, we created something more human optimized: an interactive html walkthrough (ironically, it's agent generated).
For an interactive walkthrough covering why buoyient, how it works, and setup guidance, see: [Why buoyient?](https://lauren2020.github.io/buoyient/why-buoyient.html)

---

## For AI agents: start here

**Read the guides in `docs/` before writing code.** They contain complete templates, required field tables, and common patterns. Use them instead of copying from the example services in this repo (which talk to a Square sandbox API — your endpoints, auth, and field names will be different).

| Guide | When to use |
|-------|-------------|
| [`docs/setup.md`](docs/setup.md) | Adding buoyient to an Android app: dependencies, initialization, service registration. **Start here.** |
| [`docs/creating-a-service.md`](docs/creating-a-service.md) | Creating a `SyncableObjectService`: data model, `ServerProcessingConfig`, service class, registration. |
| [`docs/integration-testing.md`](docs/integration-testing.md) | Automated JVM tests with `TestServiceEnvironment`, `MockEndpointRouter`, and the `:testing` module. |
| [`docs/mock-mode.md`](docs/mock-mode.md) | Runtime mock mode for manual testing without a real backend. |

The `templates/` directory contains copy-ready starter files for the model, request tag, server config, service, and integration test.

Agent instruction files ([`CLAUDE.md`](CLAUDE.md), [`CODEX.md`](CODEX.md), [`.cursorrules`](.cursorrules), [`.github/copilot-instructions.md`](.github/copilot-instructions.md)) are also bundled in the published JAR under `META-INF/`.

---

## Golden path: creating a new service

1. Define your `@Serializable` data model implementing `SyncableObject<T>`
2. Create a `ServiceRequestTag` enum
3. Implement `ServerProcessingConfig<T>` (sync-fetch + sync-up + headers)
4. Extend `SyncableObjectService<T, Tag>` with domain operations
5. Register the service for background sync (Hilt `@IntoSet` or `Buoyient.registerServices()`)
6. Write integration tests with `TestServiceEnvironment`

Full walkthrough with templates: [`docs/creating-a-service.md`](docs/creating-a-service.md)

---

## Modules and artifacts

| Module | Artifact | Purpose |
|--------|----------|---------|
| `:syncable-objects` | `com.les.buoyient:syncable-objects` | Core sync engine (KMP) |
| `:hilt` | `com.les.buoyient:syncable-objects-hilt` | Optional Hilt integration — auto-registers services via `@IntoSet` multibinding |
| `:testing` | `com.les.buoyient:testing` | Test utilities — mock server, in-memory DB, test doubles |

---

## Package organization

| Package | Purpose |
|---------|---------|
| `com.les.buoyient` (top level) | Primary classes: `SyncableObject`, `SyncableObjectService`, `ServiceRequestTag`, `Service` |
| `com.les.buoyient.globalconfigs` | Project-level config: `Buoyient`, `GlobalHeaderProvider`, `DatabaseProvider`, `HttpClientOverride`, `DatabaseOverride` |
| `com.les.buoyient.serviceconfigs` | Per-service config: `ServerProcessingConfig`, `SyncFetchConfig`, `SyncUpConfig`, `SyncUpResult`, `ConnectivityChecker`, `EncryptionProvider`, `PendingRequestQueueStrategy`, `SyncableObjectRebaseHandler` |
| `com.les.buoyient.datatypes` | Data types: `HttpRequest`, `SyncableObjectServiceResponse`, `SyncableObjectServiceRequestState`, `GetResponse`, `ResolveConflictResult`, `CreateRequestBuilder`, `UpdateRequestBuilder`, `VoidRequestBuilder`, `ResponseUnpacker`, `SquashRequestMerger` |
| `com.les.buoyient.utils` | Utilities: `SyncCodec`, `BuoyientLog`, `BuoyientLogger` |

Internal packages (`managers`, `sync`) are not part of the public API.

---

## Key classes

| Class | Purpose |
|-------|---------|
| `SyncableObject<O>` | Interface for your domain model (`@Serializable` data class) |
| `SyncableObjectService<O, T>` | Base class for services — `create()`, `update()`, `void()`, `get()` and flow variants `createWithFlow()`, `updateWithFlow()`, `voidWithFlow()` |
| `ServiceRequestTag` | Interface for request type enums — passed to every operation |
| `Buoyient` | Service registration, global header config, on-demand sync via `syncNow()` |
| `GlobalHeaderProvider` | Dynamic global headers (e.g., auth tokens) — set via `Buoyient.globalHeaderProvider` |
| `ServerProcessingConfig<O>` | Tells the sync engine how to talk to your API |
| `SyncFetchConfig<O>` | Configures periodic sync-down (GET or POST) |
| `SyncUpConfig<O>` | Sync-up retry logic and response parsing via `fromResponseBody()` |
| `SyncUpResult<O>` | Sealed return type: `Success(data)`, `Failed.Retry()`, or `Failed.RemovePendingRequest()` |
| `PendingRequestQueueStrategy` | `Queue` (default, one entry per op) or `Squash` (collapses consecutive offline edits) |
| `SyncableObjectRebaseHandler<O>` | 3-way merge conflict detection and resolution |
| `HttpRequest` | HTTP request builder with placeholder resolution for offline requests |
| `SyncableObjectServiceResponse<O>` | Sealed response type for all service operations |
| `SyncableObjectServiceRequestState<O>` | Sealed state for flow-based operations: `Loading` or `Result(response)` |
| `CreateRequestBuilder` / `UpdateRequestBuilder` / `VoidRequestBuilder` | Functional interfaces for building requests |
| `ResponseUnpacker` | Functional interface for extracting objects from server responses |

### Service operations

| Method | Purpose |
|--------|---------|
| `create(data, requestTag, request, unpackSyncData)` | Persist new object locally and send to server (or queue offline) |
| `update(data, requestTag, request, unpackSyncData)` | Apply changes to existing object |
| `void(data, requestTag, request, unpackSyncData)` | Mark object as voided, remove pending requests |
| `get(clientId)` | Fetch from server if online, fallback to local store |
| `getAllFromLocalStore(limit)` | All items from local DB |
| `getFromLocalStore(syncStatus, includeVoided, limit)` | SQL-level filter by sync status / voided flag |
| `getFromLocalStore(predicate, limit)` | In-memory filter via lambda |
| All `get` methods have `AsFlow` variants | Observe changes reactively |
| `syncDownFromServer()` | Trigger sync-down for this service |
| `Buoyient.syncNow(completion?)` | Trigger immediate sync-up pass across all services |

### Extension points

| Class | Override | Purpose |
|-------|----------|---------|
| `SyncableObjectService` | `create()`, `update()`, `void()`, `get()` | Define your public API |
| `SyncableObjectRebaseHandler` | `rebaseDataForPendingRequest()` | Custom 3-way merge logic |
| `SyncableObjectRebaseHandler` | `handleMergeConflict()` | Custom conflict resolution |
| `SyncUpConfig` | `acceptUploadResponseAsProcessed()` | Custom success criteria |
| `SyncUpConfig` | `fromResponseBody()` | Response deserialization for sync-up (returns `SyncUpResult`) |

---

## Critical conventions

These are the rules that cause the most agent errors when missed:

- **`serviceName` must be unique per service** — it's the SQLite partition key.

- **Constructor takes only 3 required args:** `serializer` (`KSerializer<O>`), `serverProcessingConfig`, and `serviceName`. Internal dependencies are constructed automatically — do not pass them. Optional params: `connectivityChecker`, `encryptionProvider`, `queueStrategy`, `rebaseHandler`.

- **Data models must be `@Serializable` and implement `withSyncStatus()`.** Mark `syncStatus` as `@Transient` — buoyient manages it separately.

- **Use placeholder helpers for offline requests:**
  - `HttpRequest.serverIdOrPlaceholder(serverId)` — returns real value when non-null, or `{serverId}` placeholder resolved at sync time
  - `HttpRequest.versionOrPlaceholder(version)` — same for version
  - `HttpRequest.crossServiceServerIdPlaceholder(serviceName, clientId)` — reference another service's server ID in offline requests

- **Every operation requires a `ServiceRequestTag`** and uses functional interfaces: `CreateRequestBuilder`, `UpdateRequestBuilder`, `VoidRequestBuilder`, `ResponseUnpacker`.

- **`SyncUpConfig.fromResponseBody(requestTag, responseBody)`** returns `SyncUpResult<O>`: `Success(data)`, `Failed.Retry()` (re-queue), or `Failed.RemovePendingRequest()` (drop from queue).

- **`SyncableObject` companion constants** use `_KEY` suffix: `SERVER_ID_KEY`, `CLIENT_ID_KEY`, `VERSION_KEY`.

- **Registration for background sync:** use Hilt `@IntoSet` multibinding with `:hilt`, or `Buoyient.registerServices()` / `Buoyient.registerServiceProvider()` without Hilt.

- **Queue strategy:** Use `Squash` when the API uses PUT/replace semantics and intermediate offline states don't matter. Use `Queue` (default) when request order matters or each write has side effects. See `docs/creating-a-service.md` § "Pending request queue strategy".

- **Cross-service dependencies:** Use `HttpRequest.crossServiceServerIdPlaceholder(serviceName, clientId)` when one service's request references another service's server ID. See `docs/creating-a-service.md` § "Cross-service dependencies".

- **Mock mode and integration testing:** Use `Buoyient.httpClient` and `Buoyient.database` for global overrides, or `TestServiceEnvironment` which sets them automatically.

- **Do not copy Square-specific values** from the example services in this repo. Use the consuming app's endpoints, auth, field names, and base URLs.

---

## Testing quick reference

```kotlin
testImplementation("com.les.buoyient:testing:<version>")
```

`TestServiceEnvironment` provides a fully wired harness — mock HTTP, in-memory DB, controllable connectivity:

```kotlin
@Test
fun `create item online returns server response`() = runBlocking {
    val env = TestServiceEnvironment()

    env.mockRouter.onPost("https://api.example.com/items") { request ->
        MockResponse(201, buildJsonObject {
            put("item", buildJsonObject {
                put("id", "srv-1")
                put("reference_id", request.body["reference_id"]!!)
                put("title", request.body["title"]!!)
                put("version", 1)
            })
        })
    }

    // Service constructor: only required + connectivityChecker for test control
    val service = TodoService(
        connectivityChecker = env.connectivityChecker,
    )

    val result = service.addTodo("Buy milk")
    assertTrue(result is SyncableObjectServiceResponse.Finished.NetworkResponseReceived)
    assertEquals(1, env.mockRouter.requestLog.size)

    service.close()
}
```

Toggle offline: `env.connectivityChecker.online = false`

Full testing guide: [`docs/integration-testing.md`](docs/integration-testing.md) | Mock mode guide: [`docs/mock-mode.md`](docs/mock-mode.md)

### Testing utilities

| Class | Purpose |
|-------|---------|
| `TestServiceEnvironment` | All-in-one harness: mock server, in-memory DB, test doubles |
| `MockEndpointRouter` | Register mock HTTP handlers by method + URL pattern; inspect `requestLog` |
| `MockResponse` / `RecordedRequest` | Define responses and inspect captured requests |
| `MockConnectionException` | Throw from handler to simulate network failure |
| `MockServerStore` | Stateful mock server with named collections |
| `MockServerCollection` | Per-collection CRUD, seed, mutate, inspect |
| `registerCrudHandlers()` | Auto-wire CRUD handlers backed by a collection |
| `registerSyncDownHandler()` | Auto-wire sync-down with timestamp filtering |
| `TestConnectivityChecker` | Mutable `online` flag for online/offline control |
| `IncrementingIdGenerator` | Deterministic sequential IDs (`test-id-1`, `test-id-2`, ...) |
| `PrintSyncLogger` / `NoOpSyncLogger` | Stdout or silent logging |

---

## Features

- **Offline-First** — Create, update, and void data locally when offline. Changes queue and sync when connectivity returns.
- **Bidirectional Sync** — Periodic sync-down (server to local) and on-demand sync-up (local to server).
- **3-Way Merge & Conflict Resolution** — Field-level conflict detection with pluggable merge policies via `SyncableObjectRebaseHandler`.
- **Pending Request Queue** — Persisted to SQLite, survives app restarts, supports idempotency keys.
- **Placeholder Resolution** — `{serverId}` and `{version}` placeholders resolved at sync time for safe offline chaining.
- **Request Tagging** — `ServiceRequestTag` enums for per-operation response handling.

## Platform support

| Platform | Status |
|----------|--------|
| Android  | Supported (API 27+) |
| iOS      | Beta / Experimental |

Android stack: Ktor OkHttp, SQLDelight Android driver, WorkManager for background sync, ConnectivityManager, auto-init via `androidx.startup`.

---

## Build

```bash
./gradlew :syncable-objects:build
./gradlew :hilt:build
./gradlew :testing:build
```

Run tests:

```bash
./gradlew :testing:test
```

Publish to local Maven:

```bash
./gradlew :syncable-objects:publishToMavenLocal
./gradlew :hilt:publishToMavenLocal
./gradlew :testing:publishToMavenLocal
```
