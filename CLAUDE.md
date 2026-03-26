# data-buoy

Kotlin Multiplatform offline-first sync library. Handles bidirectional sync between local SQLite and a remote server with offline queuing, conflict resolution, and automatic retries.

## For AI agents working in a consuming app

If you are integrating data-buoy into an application, **read the guides in `docs/`** before writing code:

- **`docs/setup.md`** — How to add data-buoy to an Android app: dependencies, automatic initialization, and service registration. Start here.
- **`docs/creating-a-service.md`** — Step-by-step guide to creating a `SyncableObjectService`: data model, `ServerProcessingConfig`, service class, and registration (Hilt or manual).
- **`docs/integration-testing.md`** — How to write automated JVM tests using `TestServiceEnvironment`, `MockEndpointRouter`, and the `:testing` module.
- **`docs/mock-mode.md`** — How to wire mock mode into the live app for manual testing without a real backend.

These guides contain complete templates, required field tables, and common patterns. Use them instead of copying from the example services in this repo (which talk to a Square sandbox API — your endpoints, auth, and field names will be different).

## Key classes

| Class | Purpose |
|-------|---------|
| `SyncableObject<O>` | Interface for your domain model (`@Serializable` data class) |
| `SyncableObjectService<O, T>` | Base class for services — exposes `create()`, `update()`, `void()`, `get()` and flow-based variants `createWithFlow()`, `updateWithFlow()`, `voidWithFlow()` |
| `SyncableObjectServiceRequestState<O>` | Sealed state type for flow-based operations: `Loading` or `Result(response)` |
| `getFromLocalStore()` | Overloaded query methods on `SyncableObjectService` — filter by `syncStatus` string / `includeVoided` flag (SQL-level), or by `(O) -> Boolean` predicate (in-memory) |
| `ServiceRequestTag` | Interface for request type enums — passed to every operation |
| `ServerProcessingConfig<O>` | Tells the sync engine how to talk to your API |
| `SyncFetchConfig<O>` | Configures periodic sync-down (GET or POST) |
| `SyncUpConfig<O>` | Controls sync-up retry logic and response parsing via `fromResponseBody()` |
| `SyncUpResult<O>` | Sealed return type for `fromResponseBody()`: `Success(data)`, `Failed.Retry`, or `Failed.RemovePendingRequest` |
| `SyncCodec<O>` | Serialization helper using `kotlinx.serialization.KSerializer<O>` |
| `PendingRequestQueueManager.PendingRequestQueueStrategy` | Controls how offline requests are queued: `Queue` (default, one entry per operation) or `Squash` (collapses consecutive offline edits into one request) |
| `SquashRequestMerger` | Functional interface for merging an update into a pending create when using `Squash` strategy |
| `SyncableObjectRebaseHandler<O>` | 3-way merge conflict detection and resolution |
| `SyncLog` | Process-wide logger singleton — set `SyncLog.logger` to swap the backing `SyncLogger` |
| `EncryptionProvider` | Interface for optional per-service encryption at rest — implement `encrypt()`/`decrypt()` and pass to service constructor |
| `GlobalHeaderProvider` | Functional interface for dynamic global headers (e.g., auth tokens) — set via `DataBuoy.globalHeaderProvider` |
| `DataBuoy` | Convenience API for service registration and global header configuration |
| `TestServiceEnvironment` | All-in-one test harness (`:testing` module) |
| `MockEndpointRouter` | Mock HTTP server for tests and mock mode (`:testing` module) |
| `MockServerStore` | Stateful mock server with collections (`:testing` module) |

## Modules

| Module | Artifact | Purpose |
|--------|----------|---------|
| `:data-buoy` | `com.les.databuoy:data-buoy` | Core sync engine (KMP) |
| `:hilt` | `com.les.databuoy:data-buoy-hilt` | Optional Hilt integration — auto-registers services |
| `:testing` | `com.les.databuoy:testing` | Test utilities — mock server, in-memory DB, test doubles |

## Important conventions

- `serviceName` must be unique per service — it's the SQLite partition key.
- Use `HttpRequest.serverIdOrPlaceholder(serverId)` and `HttpRequest.versionOrPlaceholder(version)` in requests for objects that may not have synced yet. These return the real value when non-null, or a placeholder that data-buoy resolves at sync time. The raw constants `HttpRequest.SERVER_ID_PLACEHOLDER` / `HttpRequest.VERSION_PLACEHOLDER` also work but the helper methods are preferred for discoverability.
- Use `HttpRequest.crossServiceServerIdPlaceholder(serviceName, clientId)` to reference another service's server ID in an offline request (e.g., a Payment referencing an Order). The first arg is the dependency's `serviceName` string; the second is the specific object's `clientId`. Resolved automatically during sync-up; skipped if the dependency hasn't synced yet. See `docs/creating-a-service.md` § "Cross-service dependencies" for a full two-service example.
- Data models must be `@Serializable` and implement `withSyncStatus()`. Mark `syncStatus` as `@Transient` — data-buoy manages it separately.
- The `SyncableObjectService` constructor requires only three arguments: `serializer` (`KSerializer<O>`), `serverProcessingConfig`, and `serviceName`. Internal dependencies (`SyncCodec`, `ServerManager`, `LocalStoreManager`, `BackgroundRequestScheduler`) are constructed automatically — do not pass them. Optional params: `connectivityChecker` (for per-service online/offline control in tests), `encryptionProvider` (encryption at rest), `queueStrategy` (pending request queue behavior), `rebaseHandler` (custom 3-way merge conflict resolution). For mock mode and integration testing, use `DataBuoy.httpClient` and `DataBuoy.database` (or `TestServiceEnvironment` which sets them automatically).
- `SyncableObject` companion constants use `_KEY` suffix: `SERVER_ID_KEY`, `CLIENT_ID_KEY`, `VERSION_KEY`.
- Every operation (`create`, `update`, `void`) requires a `ServiceRequestTag` and uses functional interfaces: `CreateRequestBuilder`, `UpdateRequestBuilder`, `VoidRequestBuilder`, `ResponseUnpacker`.
- `SyncUpConfig.fromResponseBody(requestTag, responseBody)` returns `SyncUpResult<O>`: `Success(data)`, `Failed.Retry` (re-queue), or `Failed.RemovePendingRequest` (drop from queue).
- `getAllFromLocalStore(limit)` retrieves all items from the local database. `getFromLocalStore(syncStatus, includeVoided, limit)` filters at the SQL level by sync status string and/or voided flag. `getFromLocalStore(predicate, limit)` filters in memory via a lambda. All have `AsFlow` variants.
- `SyncableObjectService` accepts an optional `queueStrategy` parameter (defaults to `Queue`). Use `Squash` when the API uses PUT/replace semantics and intermediate offline states don't matter; use `Queue` when request order matters or each write has side effects. See `docs/creating-a-service.md` § "Pending request queue strategy" for full guidance.
- Registration for background sync: use Hilt `@IntoSet` multibinding with `:hilt`, or `DataBuoy.registerServices()` / `DataBuoy.registerServiceProvider()` without Hilt.
