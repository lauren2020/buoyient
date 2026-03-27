# data-buoy

Kotlin Multiplatform offline-first sync library. Handles bidirectional sync between local SQLite and a remote server with offline queuing, conflict resolution, and automatic retries.

## For AI agents working in a consuming app

If you are integrating data-buoy into an application, **read the guides in `docs/`** before writing code:

- **`docs/setup.md`** — How to add data-buoy to an Android app: dependencies, automatic initialization, and service registration. Start here.
- **`docs/creating-a-service.md`** — Step-by-step guide to creating a `SyncableObjectService`: data model, `ServerProcessingConfig`, service class, and registration (Hilt or manual).
- **`docs/integration-testing.md`** — How to write automated JVM tests using `TestServiceEnvironment`, `MockEndpointRouter`, and the `:testing` module.
- **`docs/mock-mode.md`** — How to wire mock mode into the live app for manual testing without a real backend.

These guides contain complete templates, required field tables, and common patterns. Use them instead of copying from the example services in this repo (which talk to a Square sandbox API — your endpoints, auth, and field names will be different).

## Package organization

The `:syncable-objects` module organizes its public API into packages by role:

| Package | Purpose |
|---------|---------|
| `com.les.databuoy` (top level) | Primary classes consumers build on: `SyncableObject`, `SyncableObjectService`, `ServiceRequestTag`, `Service` |
| `com.les.databuoy.globalconfigs` | Project-level configuration: `DataBuoy`, `GlobalHeaderProvider`, `DatabaseProvider`, `HttpClientOverride`, `DatabaseOverride` |
| `com.les.databuoy.serviceconfigs` | Per-service configuration: `ServerProcessingConfig`, `SyncFetchConfig`, `SyncUpConfig`, `SyncUpResult`, `ConnectivityChecker`, `EncryptionProvider`, `PendingRequestQueueStrategy`, `SyncableObjectRebaseHandler` |
| `com.les.databuoy.syncableobjectservicedatatypes` | Data types for interacting with `SyncableObjectService`: `HttpRequest`, `SyncableObjectServiceResponse`, `SyncableObjectServiceRequestState`, `GetResponse`, `ResolveConflictResult`, `CreateRequestBuilder`, `UpdateRequestBuilder`, `VoidRequestBuilder`, `ResponseUnpacker`, `SquashRequestMerger` |
| `com.les.databuoy.utils` | Utilities: `SyncCodec`, `SyncLog`, `SyncLogger` |

Internal packages (`managers`, `sync`) are not part of the public API.

## Key classes

| Class | Package | Purpose |
|-------|---------|---------|
| `SyncableObject<O>` | top level | Interface for your domain model (`@Serializable` data class) |
| `SyncableObjectService<O, T>` | top level | Base class for services — exposes `create()`, `update()`, `void()`, `get()` and flow-based variants `createWithFlow()`, `updateWithFlow()`, `voidWithFlow()` |
| `ServiceRequestTag` | top level | Interface for request type enums — passed to every operation |
| `DataBuoy` | `globalconfigs` | Convenience API for service registration, global header configuration, and on-demand sync via `syncNow()` |
| `GlobalHeaderProvider` | `globalconfigs` | Functional interface for dynamic global headers (e.g., auth tokens) — set via `DataBuoy.globalHeaderProvider` |
| `ServerProcessingConfig<O>` | `serviceconfigs` | Tells the sync engine how to talk to your API |
| `SyncFetchConfig<O>` | `serviceconfigs` | Configures periodic sync-down (GET or POST) |
| `SyncUpConfig<O>` | `serviceconfigs` | Controls sync-up retry logic and response parsing via `fromResponseBody()` |
| `SyncUpResult<O>` | `serviceconfigs` | Sealed return type for `fromResponseBody()`: `Success(data)`, `Failed.Retry()`, or `Failed.RemovePendingRequest()` |
| `PendingRequestQueueStrategy` | `serviceconfigs` | Controls how offline requests are queued: `Queue` (default, one entry per operation) or `Squash` (collapses consecutive offline edits into one request) |
| `SyncableObjectRebaseHandler<O>` | `serviceconfigs` | 3-way merge conflict detection and resolution |
| `EncryptionProvider` | `serviceconfigs` | Interface for optional per-service encryption at rest — implement `encrypt()`/`decrypt()` and pass to service constructor |
| `ConnectivityChecker` | `serviceconfigs` | Interface for online/offline detection — pass to service constructor |
| `HttpRequest` | `syncableobjectservicedatatypes` | HTTP request builder with placeholder resolution for offline requests |
| `SyncableObjectServiceResponse<O>` | `syncableobjectservicedatatypes` | Sealed response type for all service operations |
| `SyncableObjectServiceRequestState<O>` | `syncableobjectservicedatatypes` | Sealed state type for flow-based operations: `Loading` or `Result(response)` |
| `GetResponse<O>` | `syncableobjectservicedatatypes` | Sealed response type for `get()` operations |
| `ResolveConflictResult<O>` | `syncableobjectservicedatatypes` | Sealed result type for conflict resolution |
| `CreateRequestBuilder` | `syncableobjectservicedatatypes` | Functional interface for building create requests |
| `UpdateRequestBuilder` | `syncableobjectservicedatatypes` | Functional interface for building update requests |
| `VoidRequestBuilder` | `syncableobjectservicedatatypes` | Functional interface for building void requests |
| `ResponseUnpacker` | `syncableobjectservicedatatypes` | Functional interface for extracting objects from server responses |
| `SquashRequestMerger` | `syncableobjectservicedatatypes` | Functional interface for merging an update into a pending create when using `Squash` strategy |
| `getFromLocalStore()` | (on `SyncableObjectService`) | Overloaded query methods — filter by `syncStatus` string / `includeVoided` flag (SQL-level), or by `(O) -> Boolean` predicate (in-memory) |
| `SyncCodec<O>` | `utils` | Serialization helper using `kotlinx.serialization.KSerializer<O>` |
| `SyncLog` | `utils` | Process-wide logger singleton — set `SyncLog.logger` to swap the backing `SyncLogger` |
| `TestServiceEnvironment` | `:testing` module | All-in-one test harness |
| `MockEndpointRouter` | `:testing` module | Mock HTTP server for tests and mock mode |
| `MockServerStore` | `:testing` module | Stateful mock server with collections |

## Modules

| Module | Artifact | Purpose |
|--------|----------|---------|
| `:syncable-objects` | `com.les.databuoy:syncable-objects` | Core sync engine (KMP) |
| `:hilt` | `com.les.databuoy:syncable-objects-hilt` | Optional Hilt integration — auto-registers services |
| `:testing` | `com.les.databuoy:testing` | Test utilities — mock server, in-memory DB, test doubles |

## Important conventions

- `serviceName` must be unique per service — it's the SQLite partition key.
- Use `HttpRequest.serverIdOrPlaceholder(serverId)` and `HttpRequest.versionOrPlaceholder(version)` in requests for objects that may not have synced yet. These return the real value when non-null, or a placeholder that data-buoy resolves at sync time. The raw constants `HttpRequest.SERVER_ID_PLACEHOLDER` / `HttpRequest.VERSION_PLACEHOLDER` also work but the helper methods are preferred for discoverability.
- Use `HttpRequest.crossServiceServerIdPlaceholder(serviceName, clientId)` to reference another service's server ID in an offline request (e.g., a Payment referencing an Order). The first arg is the dependency's `serviceName` string; the second is the specific object's `clientId`. Resolved automatically during sync-up; skipped if the dependency hasn't synced yet. See `docs/creating-a-service.md` § "Cross-service dependencies" for a full two-service example.
- Data models must be `@Serializable` and implement `withSyncStatus()`. Mark `syncStatus` as `@Transient` — data-buoy manages it separately.
- The `SyncableObjectService` constructor requires only three arguments: `serializer` (`KSerializer<O>`), `serverProcessingConfig`, and `serviceName`. Internal dependencies are constructed automatically — do not pass them. Optional params (from `serviceconfigs`): `connectivityChecker` (for per-service online/offline control in tests), `encryptionProvider` (encryption at rest), `queueStrategy` (pending request queue behavior), `rebaseHandler` (custom 3-way merge conflict resolution). For mock mode and integration testing, use `DataBuoy.httpClient` and `DataBuoy.database` (or `TestServiceEnvironment` which sets them automatically).
- `SyncableObject` companion constants use `_KEY` suffix: `SERVER_ID_KEY`, `CLIENT_ID_KEY`, `VERSION_KEY`.
- Every operation (`create`, `update`, `void`) requires a `ServiceRequestTag` and uses functional interfaces: `CreateRequestBuilder`, `UpdateRequestBuilder`, `VoidRequestBuilder`, `ResponseUnpacker`.
- `SyncUpConfig.fromResponseBody(requestTag, responseBody)` returns `SyncUpResult<O>`: `Success(data)`, `Failed.Retry()` (re-queue), or `Failed.RemovePendingRequest()` (drop from queue).
- `getAllFromLocalStore(limit)` retrieves all items from the local database. `getFromLocalStore(syncStatus, includeVoided, limit)` filters at the SQL level by sync status string and/or voided flag. `getFromLocalStore(predicate, limit)` filters in memory via a lambda. All have `AsFlow` variants.
- `SyncableObjectService` accepts an optional `queueStrategy` parameter (defaults to `Queue`). Use `Squash` when the API uses PUT/replace semantics and intermediate offline states don't matter; use `Queue` when request order matters or each write has side effects. See `docs/creating-a-service.md` § "Pending request queue strategy" for full guidance.
- Registration for background sync: use Hilt `@IntoSet` multibinding with `:hilt`, or `DataBuoy.registerServices()` / `DataBuoy.registerServiceProvider()` without Hilt.
- Use `DataBuoy.syncNow()` to trigger an immediate sync-up pass from the UI (e.g. pull-to-refresh). It runs on a background coroutine and accepts an optional `completion: (Boolean) -> Unit` callback. For per-service sync-down, call `service.syncDownFromServer()` directly.
