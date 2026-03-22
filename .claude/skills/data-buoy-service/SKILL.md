---
name: data-buoy-service
description: "How to create and register new SyncableObjectService implementations using the data-buoy offline-first sync library. Use this skill whenever the user wants to add a new data type or service to an app that uses data-buoy, create a syncable model, build CRUD operations with offline support, set up background sync for a new entity, or integrate a new API endpoint with data-buoy's sync engine. Also trigger when the user mentions SyncableObject, SyncableObjectService, ServerProcessingConfig, or asks about offline-first data patterns in a Kotlin/Android context."
---

Read the guide at `docs/creating-a-service.md` for the complete, up-to-date instructions on creating a data-buoy service. That file is the authoritative reference and is kept in sync with the actual codebase.

Key points to remember:
- Data models must be `@Serializable` data classes implementing `SyncableObject<O>` with `withSyncStatus()` and `@Transient syncStatus`.
- Services extend `SyncableObjectService<O, T>` where `T : ServiceRequestTag`. The constructor takes a `KSerializer<O>` (not a manual deserializer).
- Every operation (`create`, `update`, `void`) requires a `ServiceRequestTag` and uses functional interfaces: `CreateRequestBuilder`, `UpdateRequestBuilder`, `VoidRequestBuilder`, `ResponseUnpacker`.
- `SyncUpConfig.fromResponseBody(requestTag, responseBody)` parses sync-up server responses.
- `ServerProcessingConfig.globalHeaders` (not `headers`) provides HTTP headers for every request.
- `getAllFromLocalStore(limit)` retrieves all items from the local database.
- `SyncableObject` companion constants use `_KEY` suffix: `SERVER_ID_KEY`, `CLIENT_ID_KEY`, `VERSION_KEY`.
