---
name: buoyient-service
description: "How to create and register new SyncableObjectService implementations using the buoyient offline-first sync library. Use this skill whenever the user wants to add a new data type or service to an app that uses buoyient, create a syncable model, build CRUD operations with offline support, set up background sync for a new entity, or integrate a new API endpoint with buoyient's sync engine. Also trigger when the user mentions SyncableObject, SyncableObjectService, ServerProcessingConfig, or asks about offline-first data patterns in a Kotlin/Android context."
---

Read the guide at `docs/creating-a-service.md` for the complete, up-to-date instructions on creating a buoyient service. That file is the authoritative reference and is kept in sync with the actual codebase.

Key points to remember:
- Data models must be `@Serializable` data classes implementing `SyncableObject<O>` with `withSyncStatus()` and `@Transient syncStatus`.
- Services extend `SyncableObjectService<O, T>` where `T : ServiceRequestTag`. The constructor takes a `KSerializer<O>` (not a manual deserializer).
- Service-level configuration classes (`ServerProcessingConfig`, `SyncFetchConfig`, `SyncUpConfig`, `SyncUpResult`, `ConnectivityChecker`, `EncryptionProvider`, `PendingRequestQueueStrategy`, `SyncableObjectRebaseHandler`) are in the `com.elvdev.buoyient.serviceconfigs` package.
- Data types used when interacting with `SyncableObjectService` (`HttpRequest`, `SyncableObjectServiceResponse`, `SyncableObjectServiceRequestState`, `GetResponse`, `CreateRequestBuilder`, `UpdateRequestBuilder`, `VoidRequestBuilder`, `ResponseUnpacker`, `SquashRequestMerger`) are in the `com.elvdev.buoyient.datatypes` package.
- Every operation (`create`, `update`, `void`) requires a `ServiceRequestTag` and uses functional interfaces: `CreateRequestBuilder`, `UpdateRequestBuilder`, `VoidRequestBuilder`, `ResponseUnpacker`.
- `SyncUpConfig.fromResponseBody(requestTag, responseBody)` returns `SyncUpResult<O>`: `Success(data)`, `Failed.Retry` (re-queue), or `Failed.RemovePendingRequest` (drop from queue).
- `ServerProcessingConfig.serviceHeaders` provides per-service HTTP headers. For auth headers shared across all services, use `Buoyient.globalHeaderProvider` (a `GlobalHeaderProvider` lambda evaluated per-request, in `com.elvdev.buoyient.globalconfigs`).
- `getAllFromLocalStore(limit)` retrieves all items from the local database.
- `LocalStoreManager` accepts an optional `queueStrategy`: `Queue` (default) stores every operation separately; `Squash` collapses consecutive offline edits into one request. Use `Squash` for PUT/replace APIs where intermediate states don't matter. See `docs/creating-a-service.md` § "Pending request queue strategy".
- `SyncableObject` companion constants use `_KEY` suffix: `SERVER_ID_KEY`, `CLIENT_ID_KEY`, `VERSION_KEY`.
