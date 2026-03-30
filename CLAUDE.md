# buoyient

Kotlin Multiplatform offline-first sync library. Handles bidirectional sync between local SQLite and a remote server with offline queuing, conflict resolution, and automatic retries.

## For AI agents working in a consuming app

If you are integrating buoyient into an application, **read the guides in `docs/`** before writing code:

- **`docs/setup.md`** — How to add buoyient to an Android app: dependencies, automatic initialization, and service registration. Start here.
- **`docs/creating-a-service.md`** — Step-by-step guide to creating a `SyncableObjectService`: data model, `ServerProcessingConfig`, service class, and registration (Hilt or manual).
- **`docs/integration-testing.md`** — How to write automated JVM tests using `TestServiceEnvironment`, `MockEndpointRouter`, and the `:testing` module.
- **`docs/mock-mode.md`** — How to wire mock mode into the live app for manual testing without a real backend.

These guides contain complete templates, required field tables, and common patterns. Use them instead of copying from the example services in this repo (which talk to a Square sandbox API — your endpoints, auth, and field names will be different).

## Package organization

The `:syncable-objects` module organizes its public API into packages by role:

| Package | Purpose |
|---------|---------|
| `com.elvdev.buoyient` (top level) | Primary classes consumers build on: `SyncableObject`, `SyncableObjectService`, `ServiceRequestTag`, `Service` |
| `com.elvdev.buoyient.globalconfigs` | Project-level configuration: `Buoyient`, `GlobalHeaderProvider`, `DatabaseProvider`, `HttpClientOverride`, `DatabaseOverride` |
| `com.elvdev.buoyient.serviceconfigs` | Per-service configuration: `ServerProcessingConfig`, `SyncFetchConfig`, `SyncUpConfig`, `SyncUpResult`, `ConnectivityChecker`, `EncryptionProvider`, `PendingRequestQueueStrategy`, `SyncableObjectRebaseHandler` |
| `com.elvdev.buoyient.datatypes` | Data types for interacting with `SyncableObjectService`: `HttpRequest`, `SyncableObjectServiceResponse`, `SyncableObjectServiceRequestState`, `GetResponse`, `ResolveConflictResult`, `CreateRequestBuilder`, `UpdateRequestBuilder`, `VoidRequestBuilder`, `ResponseUnpacker`, `SquashRequestMerger` |
| `com.elvdev.buoyient.utils` | Utilities: `SyncCodec`, `BuoyientLog`, `BuoyientLogger` |

Internal packages (`managers`, `sync`) are not part of the public API.

## Key classes

| Class | Package | Purpose |
|-------|---------|---------|
| `SyncableObject<O>` | top level | Interface for your domain model (`@Serializable` data class) |
| `SyncableObjectService<O, T>` | top level | Base class for services — exposes `create()`, `update()`, `void()`, `get()` and flow-based variants `createWithFlow()`, `updateWithFlow()`, `voidWithFlow()` |
| `ServiceRequestTag` | top level | Interface for request type enums — passed to every operation |
| `Buoyient` | `globalconfigs` | Convenience API for service registration, global header configuration, and on-demand sync via `syncNow()` |
| `GlobalHeaderProvider` | `globalconfigs` | Functional interface for dynamic global headers (e.g., auth tokens) — set via `Buoyient.globalHeaderProvider` |
| `ServerProcessingConfig<O>` | `serviceconfigs` | Tells the sync engine how to talk to your API |
| `SyncFetchConfig<O>` | `serviceconfigs` | Configures periodic sync-down (GET or POST) |
| `SyncUpConfig<O>` | `serviceconfigs` | Controls sync-up retry logic and response parsing via `fromResponseBody()` |
| `SyncUpResult<O>` | `serviceconfigs` | Sealed return type for `fromResponseBody()`: `Success(data)`, `Failed.Retry()`, or `Failed.RemovePendingRequest()` |
| `PendingRequestQueueStrategy` | `serviceconfigs` | Controls how offline requests are queued: `Queue` (default, one entry per operation) or `Squash` (collapses consecutive offline edits into one request) |
| `SyncableObjectRebaseHandler<O>` | `serviceconfigs` | 3-way merge conflict detection and resolution |
| `EncryptionProvider` | `serviceconfigs` | Interface for optional per-service encryption at rest — implement `encrypt()`/`decrypt()` and pass to service constructor |
| `ConnectivityChecker` | `serviceconfigs` | Interface for online/offline detection — pass to service constructor |
| `HttpRequest` | `datatypes` | HTTP request builder with placeholder resolution for offline requests |
| `SyncableObjectServiceResponse<O>` | `datatypes` | Sealed response type for all service operations. Extension helpers: `dataOrNull()`, `onSuccess {}`, `onFailure {}`, `isSuccess`, `isFailure` |
| `SyncableObjectServiceRequestState<O>` | `datatypes` | Sealed state type for flow-based operations: `Loading` or `Result(response)` |
| `GetResponse<O>` | `datatypes` | Sealed response type for `get()` operations |
| `ResolveConflictResult<O>` | `datatypes` | Sealed result type for conflict resolution |
| `CreateRequestBuilder` | `datatypes` | Functional interface for building create requests |
| `UpdateRequestBuilder` | `datatypes` | Functional interface for building update requests |
| `VoidRequestBuilder` | `datatypes` | Functional interface for building void requests |
| `ResponseUnpacker` | `datatypes` | Functional interface for extracting objects from server responses |
| `SquashRequestMerger` | `datatypes` | Functional interface for merging an update into a pending create when using `Squash` strategy |
| `getFromLocalStore()` | (on `SyncableObjectService`) | Overloaded query methods — filter by `syncStatus` string / `includeVoided` flag (SQL-level), or by `(O) -> Boolean` predicate (in-memory) |
| `SyncCodec<O>` | `utils` | Serialization helper using `kotlinx.serialization.KSerializer<O>` |
| `BuoyientLog` | `utils` | Process-wide logger singleton — set `BuoyientLog.logger` to swap the backing `BuoyientLogger` |
| `TestServiceEnvironment` | `:testing` module | All-in-one test harness |
| `MockEndpointRouter` | `:mock-infra` module | Mock HTTP server for tests and mock mode |
| `MockServerStore` | `:mock-infra` module | Stateful mock server with collections |
| `MockEndpoint` | `:mock-infra` module | Declares a mock HTTP endpoint with method, URL pattern, label, and handler |
| `MockEndpointController` | `:mock-infra` module | Runtime-configurable controller for toggling endpoint failure overrides (server errors, connection errors, timeouts) |
| `FailureOverride` | `:mock-infra` module | Sealed interface for failure modes: `ServerError(statusCode)`, `Timeout(serverReceivedRequest)` |
| `MockServiceServer` | `:mock-mode` module | Abstract base class for self-contained mock servers — subclass to define seed data and declare endpoints per service |
| `MockModeBuilder` | `:mock-mode` module | Quick-start builder for mock mode setup — accepts `MockServiceServer` instances and installs global overrides |
| `MockModeHandle` | `:mock-mode` module | Handle returned by `MockModeBuilder.install()` with references to router, store, connectivity checker, endpoint index, and endpoint controller |

## Modules

| Module | Artifact | Purpose |
|--------|----------|---------|
| `:syncable-objects` | `com.elvdev.buoyient:syncable-objects` | Core sync engine (KMP) |
| `:hilt` | `com.elvdev.buoyient:syncable-objects-hilt` | Optional Hilt integration — auto-registers services |
| `:mock-infra` | `com.elvdev.buoyient:syncable-objects-mock-infra` | Shared mock infrastructure — mock HTTP routing, stateful server store, test doubles |
| `:mock-mode` | `com.elvdev.buoyient:syncable-objects-mock-mode` | Mock mode builder for running apps against fake server responses |
| `:testing` | `com.elvdev.buoyient:syncable-objects-testing` | Test utilities — in-memory DB, test harness, sync helpers |

## Important conventions

- `serviceName` must be unique per service — it's the SQLite partition key.
- Use `HttpRequest.serverIdOrPlaceholder(serverId)` and `HttpRequest.versionOrPlaceholder(version)` in requests for objects that may not have synced yet. These return the real value when non-null, or a placeholder that buoyient resolves at sync time. The raw constants `HttpRequest.SERVER_ID_PLACEHOLDER` / `HttpRequest.VERSION_PLACEHOLDER` also work but the helper methods are preferred for discoverability.
- Use `HttpRequest.crossServiceServerIdPlaceholder(serviceName, clientId)` to reference another service's server ID in an offline request (e.g., a Payment referencing an Order). The first arg is the dependency's `serviceName` string; the second is the specific object's `clientId`. Resolved automatically during sync-up; skipped if the dependency hasn't synced yet. See `docs/creating-a-service.md` § "Cross-service dependencies" for a full two-service example.
- Data models must be `@Serializable` and implement `withSyncStatus()`. Mark `syncStatus` as `@Transient` — buoyient manages it separately.
- The `SyncableObjectService` constructor requires only three arguments: `serializer` (`KSerializer<O>`), `serverProcessingConfig`, and `serviceName`. Internal dependencies are constructed automatically — do not pass them. Optional params (from `serviceconfigs`): `connectivityChecker` (for per-service online/offline control in tests), `encryptionProvider` (encryption at rest), `queueStrategy` (pending request queue behavior), `rebaseHandler` (custom 3-way merge conflict resolution). For mock mode and integration testing, use `Buoyient.httpClient` and `Buoyient.database` (or `TestServiceEnvironment` which sets them automatically).
- `SyncableObject` companion constants use `_KEY` suffix: `SERVER_ID_KEY`, `CLIENT_ID_KEY`, `VERSION_KEY`.
- Every operation (`create`, `update`, `void`) requires a `ServiceRequestTag` and uses functional interfaces: `CreateRequestBuilder`, `UpdateRequestBuilder`, `VoidRequestBuilder`, `ResponseUnpacker`.
- `SyncUpConfig.fromResponseBody(requestTag, responseBody)` returns `SyncUpResult<O>`: `Success(data)`, `Failed.Retry()` (re-queue), or `Failed.RemovePendingRequest()` (drop from queue).
- `getAllFromLocalStore(limit)` retrieves all items from the local database. `getFromLocalStore(syncStatus, includeVoided, limit)` filters at the SQL level by sync status string and/or voided flag. `getFromLocalStore(predicate, limit)` filters in memory via a lambda. All have `AsFlow` variants.
- `SyncableObjectService` accepts an optional `queueStrategy` parameter (defaults to `Queue`). Use `Squash` when the API uses PUT/replace semantics and intermediate offline states don't matter; use `Queue` when request order matters or each write has side effects. See `docs/creating-a-service.md` § "Pending request queue strategy" for full guidance.
- Registration for background sync: use Hilt `@IntoSet` multibinding with `:hilt`, or `Buoyient.registerServices()` / `Buoyient.registerServiceProvider()` without Hilt.
- Use `Buoyient.syncNow()` to trigger an immediate sync-up pass from the UI (e.g. pull-to-refresh). It runs on a background coroutine and accepts an optional `completion: (Boolean) -> Unit` callback. For per-service sync-down, call `service.syncDownFromServer()` directly.
