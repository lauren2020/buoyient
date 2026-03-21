# data-buoy

Kotlin Multiplatform offline-first sync library. Handles bidirectional sync between local SQLite and a remote server with offline queuing, conflict resolution, and automatic retries.

## For AI agents working in a consuming app

If you are integrating data-buoy into an application, **read the guides in `docs/`** before writing code:

- **`docs/creating-a-service.md`** — Step-by-step guide to creating a `SyncableObjectService`: data model, `ServerProcessingConfig`, service class, and registration (Hilt or manual). Start here.
- **`docs/integration-testing.md`** — How to write automated JVM tests using `TestServiceEnvironment`, `MockEndpointRouter`, and the `:testing` module.
- **`docs/mock-mode.md`** — How to wire mock mode into the live app for manual testing without a real backend.

These guides contain complete templates, required field tables, and common patterns. Use them instead of copying from the example services in this repo (which talk to a Square sandbox API — your endpoints, auth, and field names will be different).

## Key classes

| Class | Purpose |
|-------|---------|
| `SyncableObject<T>` | Interface for your domain model |
| `SyncableObjectService<T>` | Base class for services — exposes `create()`, `update()`, `void()` |
| `ServerProcessingConfig<T>` | Tells the sync engine how to talk to your API |
| `DataBuoy` | Convenience API for service registration |
| `TestServiceEnvironment` | All-in-one test harness (`:testing` module) |
| `MockEndpointRouter` | Mock HTTP server for tests and mock mode (`:testing` module) |
| `MockServerStore` | Stateful mock server with collections (`:testing` module) |

## Modules

| Module | Artifact | Purpose |
|--------|----------|---------|
| `:library` | `com.les.databuoy:library` | Core sync engine (KMP) |
| `:hilt` | `com.les.databuoy:data-buoy-hilt` | Optional Hilt integration — auto-registers services |
| `:testing` | `com.les.databuoy:testing` | Test utilities — mock server, in-memory DB, test doubles |

## Important conventions

- `serviceName` must be unique per service — it's the SQLite partition key.
- Use `HttpRequest.SERVER_ID_PLACEHOLDER` (`{serverId}`) and `HttpRequest.VERSION_PLACEHOLDER` (`{version}`) in requests for objects that may not have synced yet.
- `SyncableObject.toJson()` must include `SERVER_ID_TAG`, `CLIENT_ID_TAG`, and `VERSION_TAG`.
- `fromJson()` accepts a `syncStatus` parameter — data-buoy manages sync status separately.
- Registration for background sync: use Hilt `@IntoSet` multibinding with `:hilt`, or `DataBuoy.registerServices()` / `DataBuoy.registerServiceProvider()` without Hilt.
