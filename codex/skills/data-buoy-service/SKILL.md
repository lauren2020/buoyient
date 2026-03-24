---
name: data-buoy-service
description: "How to create and register new SyncableObjectService implementations using the data-buoy offline-first sync library. Use this skill whenever the user wants to add a new data type or service to an app that uses data-buoy, create a syncable model, build CRUD operations with offline support, set up background sync for a new entity, or integrate a new API endpoint with data-buoy's sync engine. Also trigger when the user mentions SyncableObject, SyncableObjectService, ServerProcessingConfig, or asks about offline-first data patterns in a Kotlin/Android context."
---

Use `docs/creating-a-service.md` as the authoritative service-building guide, and consult `CODEX.md` for the shared project conventions.

Key points to remember:
- Data models must be `@Serializable` data classes implementing `SyncableObject<O>` with `withSyncStatus()` and `@Transient syncStatus`.
- Services extend `SyncableObjectService<O, T>` where `T : ServiceRequestTag`. The constructor takes a `KSerializer<O>` rather than a manual deserializer.
- Every operation (`create`, `update`, `void`) requires a `ServiceRequestTag` and uses functional interfaces: `CreateRequestBuilder`, `UpdateRequestBuilder`, `VoidRequestBuilder`, `ResponseUnpacker`.
- `SyncUpConfig.fromResponseBody(requestTag, responseBody)` returns `SyncUpResult<O>`: `Success(data)`, `Failed.Retry` (re-queue), or `Failed.RemovePendingRequest` (drop from queue).
- `ServerProcessingConfig.globalHeaders` (not `headers`) provides HTTP headers for every request.
- `getAllFromLocalStore(limit)` retrieves all items from the local database.
- `LocalStoreManager` accepts an optional `queueStrategy`: `Queue` (default) stores every operation separately; `Squash` collapses consecutive offline edits into one request. Use `Squash` for PUT/replace APIs where intermediate states don't matter. See `docs/creating-a-service.md` § "Pending request queue strategy".
- `SyncableObject` companion constants use `_KEY` suffix: `SERVER_ID_KEY`, `CLIENT_ID_KEY`, `VERSION_KEY`.
