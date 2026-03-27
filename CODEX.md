# data-buoy

Kotlin Multiplatform offline-first sync library. Handles bidirectional sync between local SQLite and a remote server with offline queuing, conflict resolution, and automatic retries.

## For Codex agents working in a consuming app

If you are integrating data-buoy into an application, start with the guides in `docs/` and the Codex skill files in `codex/skills/` before writing code:

- `docs/setup.md` - How to add data-buoy to an Android app: dependencies, automatic initialization, and service registration.
- `docs/creating-a-service.md` - How to create a `SyncableObjectService`: model, request tags, server config, and registration.
- `docs/integration-testing.md` - How to write automated JVM tests using `TestServiceEnvironment`, `MockEndpointRouter`, and the `:testing` module.
- `docs/mock-mode.md` - How to wire mock mode into the live app for manual testing without a real backend.

## Golden Path

When adding a new `SyncableObjectService`, follow this sequence and keep your work close to the canonical assets in this repo:

1. Copy the starter files in `templates/`:
   - `templates/YourModel.kt.template`
   - `templates/YourModelRequestTag.kt.template`
   - `templates/YourModelServerProcessingConfig.kt.template`
   - `templates/YourModelService.kt.template`
   - `templates/YourModelServiceTest.kt.template`
2. Replace placeholders with your domain names, endpoints, headers, request tags, and response paths.
3. Use `examples/todo/` as the minimal end-to-end reference for how the pieces fit together.
4. Register the service using the patterns in `docs/creating-a-service.md`:
   - Hilt `@IntoSet` multibinding when using `:hilt`
   - `DataBuoy.registerServices(...)` or `DataBuoy.registerServiceProvider(...)` otherwise
5. Write or adapt the integration test so it proves:
   - online create returns server data
   - offline create stores locally
   - queued work syncs once connectivity returns

The expected file creation order is:

1. Create the model implementing `SyncableObject<O>`.
2. Define the request tag enum implementing `ServiceRequestTag`.
3. Implement the server config (`ServerProcessingConfig<O>`).
4. Build the service (`SyncableObjectService<O, T>`).
5. Register the service.
6. Add an integration test with `TestServiceEnvironment`.

If you are unsure about naming or wiring, prefer copying from `templates/` and `examples/todo/` instead of inventing a new structure.

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
| `PendingRequestQueueStrategy` | Controls how offline requests are queued: `Queue` (default, one entry per operation) or `Squash` (collapses consecutive offline edits into one request) |
| `SquashRequestMerger` | Functional interface for merging an update into a pending create when using `Squash` strategy |
| `SyncableObjectRebaseHandler<O>` | 3-way merge conflict detection and resolution |
| `DataBuoy` | Convenience API for service registration |
| `TestServiceEnvironment` | All-in-one test harness (`:testing` module) |
| `MockEndpointRouter` | Mock HTTP server for tests and mock mode (`:testing` module) |
| `MockServerStore` | Stateful mock server with collections (`:testing` module) |

## Modules

| Module | Artifact | Purpose |
|--------|----------|---------|
| `:syncable-objects` | `com.les.databuoy:syncable-objects` | Core sync engine (KMP) |
| `:hilt` | `com.les.databuoy:syncable-objects-hilt` | Optional Hilt integration - auto-registers services |
| `:testing` | `com.les.databuoy:testing` | Test utilities - mock server, in-memory DB, test doubles |

## Important conventions

- `serviceName` must be unique per service - it's the SQLite partition key.
- Use `HttpRequest.SERVER_ID_PLACEHOLDER` (`{serverId}`) and `HttpRequest.VERSION_PLACEHOLDER` (`{version}`) in requests for objects that may not have synced yet.
- Data models must be `@Serializable` and implement `withSyncStatus()`. Mark `syncStatus` as `@Transient` - data-buoy manages it separately.
- The `SyncableObjectService` constructor requires only three arguments: `serializer` (`KSerializer<O>`), `serverProcessingConfig`, and `serviceName`. Internal dependencies (`SyncCodec`, `ServerManager`, `LocalStoreManager`, `BackgroundRequestScheduler`) are constructed automatically — do not pass them. Optional params: `connectivityChecker` (for per-service online/offline control in tests), `encryptionProvider` (encryption at rest), `queueStrategy` (pending request queue behavior). For mock mode and integration testing, use `DataBuoy.httpClient` and `DataBuoy.database` (or `TestServiceEnvironment` which sets them automatically).
- `SyncableObject` companion constants use `_KEY` suffix: `SERVER_ID_KEY`, `CLIENT_ID_KEY`, `VERSION_KEY`.
- Every operation (`create`, `update`, `void`) requires a `ServiceRequestTag` and uses functional interfaces: `CreateRequestBuilder`, `UpdateRequestBuilder`, `VoidRequestBuilder`, `ResponseUnpacker`.
- `SyncUpConfig.fromResponseBody(requestTag, responseBody)` returns `SyncUpResult<O>`: `Success(data)`, `Failed.Retry` (re-queue), or `Failed.RemovePendingRequest` (drop from queue).
- `getAllFromLocalStore(limit)` retrieves all items from the local database.
- `LocalStoreManager` accepts an optional `queueStrategy` parameter (defaults to `Queue`). Use `Squash` when the API uses PUT/replace semantics and intermediate offline states don't matter; use `Queue` when request order matters or each write has side effects. See `docs/creating-a-service.md` § "Pending request queue strategy" for full guidance.
- Registration for background sync: use Hilt `@IntoSet` multibinding with `:hilt`, or `DataBuoy.registerServices()` / `DataBuoy.registerServiceProvider()` without Hilt.
