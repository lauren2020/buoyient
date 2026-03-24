# data-buoy

Kotlin Multiplatform offline-first sync library. Handles bidirectional sync between local SQLite and a remote server with offline queuing, conflict resolution, and automatic retries.

## For Codex agents working in a consuming app

If you are integrating data-buoy into an application, start with the guides in `docs/` and the Codex skill files in `codex/skills/` before writing code:

- `docs/setup.md` - How to add data-buoy to an Android app: dependencies, automatic initialization, and service registration.
- `docs/creating-a-service.md` - How to create a `SyncableObjectService`: model, request tags, server config, and registration.
- `docs/integration-testing.md` - How to write automated JVM tests using `TestServiceEnvironment`, `MockEndpointRouter`, and the `:testing` module.
- `docs/mock-mode.md` - How to wire mock mode into the live app for manual testing without a real backend.

## Key classes

| Class | Purpose |
|-------|---------|
| `SyncableObject<O>` | Interface for your domain model (`@Serializable` data class) |
| `SyncableObjectService<O, T>` | Base class for services - exposes `create()`, `update()`, `void()`, `get()` and flow-based variants |
| `SyncableObjectServiceRequestState<O>` | Sealed state type for flow-based operations: `Loading` or `Result(response)` |
| `ServiceRequestTag` | Interface for request type enums - passed to every operation |
| `ServerProcessingConfig<O>` | Tells the sync engine how to talk to your API |
| `SyncFetchConfig<O>` | Configures periodic sync-down (GET or POST) |
| `SyncUpConfig<O>` | Controls sync-up retry logic and response parsing via `fromResponseBody()` |
| `SyncCodec<O>` | Serialization helper using `kotlinx.serialization.KSerializer<O>` |
| `SyncableObjectRebaseHandler<O>` | 3-way merge conflict detection and resolution |
| `DataBuoy` | Convenience API for service registration |
| `TestServiceEnvironment` | All-in-one test harness (`:testing` module) |
| `MockEndpointRouter` | Mock HTTP server for tests and mock mode (`:testing` module) |
| `MockServerStore` | Stateful mock server with collections (`:testing` module) |

## Modules

| Module | Artifact | Purpose |
|--------|----------|---------|
| `:library` | `com.les.databuoy:library` | Core sync engine (KMP) |
| `:hilt` | `com.les.databuoy:data-buoy-hilt` | Optional Hilt integration - auto-registers services |
| `:testing` | `com.les.databuoy:testing` | Test utilities - mock server, in-memory DB, test doubles |

## Important conventions

- `serviceName` must be unique per service - it's the SQLite partition key.
- Use `HttpRequest.SERVER_ID_PLACEHOLDER` (`{serverId}`) and `HttpRequest.VERSION_PLACEHOLDER` (`{version}`) in requests for objects that may not have synced yet.
- Data models must be `@Serializable` and implement `withSyncStatus()`. Mark `syncStatus` as `@Transient` - data-buoy manages it separately.
- The service constructor takes a `KSerializer<O>` from `kotlinx.serialization`, not a manual deserializer.
- `SyncableObject` companion constants use `_KEY` suffix: `SERVER_ID_KEY`, `CLIENT_ID_KEY`, `VERSION_KEY`.
- Every operation (`create`, `update`, `void`) requires a `ServiceRequestTag` and uses functional interfaces: `CreateRequestBuilder`, `UpdateRequestBuilder`, `VoidRequestBuilder`, `ResponseUnpacker`.
- `SyncUpConfig.fromResponseBody(requestTag, responseBody)` returns `SyncUpResult<O>`: `Success(data)`, `Failed.Retry` (re-queue), or `Failed.RemovePendingRequest` (drop from queue).
- `getAllFromLocalStore(limit)` retrieves all items from the local database.
- Registration for background sync: use Hilt `@IntoSet` multibinding with `:hilt`, or `DataBuoy.registerServices()` / `DataBuoy.registerServiceProvider()` without Hilt.
