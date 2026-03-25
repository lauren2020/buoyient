# Creating a data-buoy Service

This guide walks through creating a complete offline-first syncable service using data-buoy. A service consists of four pieces that work together:

1. **Data model** — a `@Serializable` data class implementing `SyncableObject<T>` that defines your domain entity
2. **Request tag** — a `ServiceRequestTag` enum that identifies different request types (create, update, void)
3. **Server processing config** — a `ServerProcessingConfig<T>` that tells data-buoy how to communicate with your API
4. **Service class** — a `SyncableObjectService<T, Tag>` subclass that exposes create/update/void operations
5. **Registration** — registering the service so background sync picks it up (via `DataBuoy` API, Hilt multibinding, or a manual `SyncServiceRegistryProvider`)

The library handles online/offline dual-path execution, local SQLite persistence, pending request queuing, idempotent retries, background sync via WorkManager, and 3-way merge conflict resolution automatically. Your job is just to define the shape of your data and how to talk to your API.

Before writing code, start from the canonical assets in this repo:

- `templates/` contains copy-ready starter files for the model, request tag, server config, service, and integration test.
- `examples/todo/` shows the same workflow as a minimal working integration.
- `CODEX.md` summarizes the golden-path file order for agents.

**Important: use the user's API, not the examples.** The existing services in this codebase (PaymentService, OrderService) talk to a specific Square sandbox API. When creating a new service, use the endpoints, auth headers, field names, and base URLs that the consuming app specifies — do not copy Square-specific values like `connect.squareupsandbox.com`, the Square Bearer token, `location_id: "LD0K6CFYP3DP7"`, or Square-Version headers into a new service. The existing services are useful as structural references for *how* to use data-buoy's APIs, but the concrete URLs, credentials, and field mappings must come from the consumer's requirements.

---

## Golden Path Checklist

Use this order when creating a new service:

1. Create the model.
2. Define the request tag enum.
3. Implement the server config.
4. Build the service.
5. Register the service.
6. Add an integration test.

If you can, copy from `templates/` first and only then customize the API-specific details.

---

## Step 1: Create the Data Model

Create a `@Serializable` data class that implements `SyncableObject<YourModel>`. This is the domain object that data-buoy persists and syncs.

### Required interface members

Every `SyncableObject` must have:

| Field | Type | Purpose |
|-------|------|---------|
| `serverId` | `String?` | Server's ID. `null` until first sync. |
| `clientId` | `String` | Locally-generated UUID. Stable identifier regardless of sync state. |
| `version` | `Int` | Incremented on each update. Used for optimistic concurrency. |
| `syncStatus` | `SyncStatus` | Tracks sync lifecycle (LocalOnly, PendingCreate, Synced, Conflict, etc.) |
| `withSyncStatus(syncStatus)` | `fun` | Returns a copy of the object with the given sync status. |

### Companion constants

The `SyncableObject` companion object provides constants for the metadata keys used in serialized JSON. These are managed by data-buoy internally:

| Constant | Value |
|----------|-------|
| `SERVER_ID_KEY` | `"server_id"` |
| `CLIENT_ID_KEY` | `"client_id"` |
| `VERSION_KEY` | `"version"` |
| `SYNC_STATUS_KEY` | `"sync_status"` |

### Template

```kotlin
package com.example.yourapp.data.models

import com.les.databuoy.SyncableObject
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.UUID

@Serializable
data class YourModel(
    override val serverId: String? = null,
    override val clientId: String = UUID.randomUUID().toString(),
    override val version: Int = 0,
    @Transient override val syncStatus: SyncableObject.SyncStatus = SyncableObject.SyncStatus.LocalOnly,
    // Add your domain-specific fields here:
    val name: String,
    val amount: Long,
    val status: String = "DRAFT",
) : SyncableObject<YourModel> {

    override fun withSyncStatus(syncStatus: SyncableObject.SyncStatus): YourModel =
        copy(syncStatus = syncStatus)
}
```

### Key rules

- The class must be annotated with `@Serializable` — data-buoy uses `kotlinx.serialization` via `SyncCodec` to serialize/deserialize objects.
- `syncStatus` must be marked `@Transient` — data-buoy manages sync status separately from the JSON blob stored in SQLite.
- `withSyncStatus()` must return a copy with the new status. For data classes, just delegate to `copy(syncStatus = syncStatus)`.
- Default values for `serverId = null`, `clientId = UUID.randomUUID().toString()`, `version = 0`, and `syncStatus = LocalOnly` provide a convenient constructor for creating new local-only instances.

---

## Step 2: Create the ServiceRequestTag

Define an enum implementing `ServiceRequestTag` to identify the different request types your service makes. This tag is stored with pending requests and passed to `SyncUpConfig.fromResponseBody()` so you can parse different response shapes per request type.

```kotlin
package com.example.yourapp.data.services

import com.les.databuoy.ServiceRequestTag

enum class YourModelRequestTag(override val value: String) : ServiceRequestTag {
    CREATE("create"),
    UPDATE("update"),
    VOID("void"),
}
```

---

## Step 3: Create the ServerProcessingConfig

Implement `ServerProcessingConfig<YourModel>` to tell data-buoy how to fetch data from and push data to your server.

### Required members

| Member | Type | Purpose |
|--------|------|---------|
| `syncFetchConfig` | `SyncFetchConfig<YourModel>` | How to periodically pull data from the server (GET or POST). |
| `syncUpConfig` | `SyncUpConfig<YourModel>` | Controls retry behavior and response parsing for sync-up uploads. Must implement `fromResponseBody(requestTag, responseBody)` returning `SyncUpResult<YourModel>` — `Success(data)`, `Failed.Retry`, or `Failed.RemovePendingRequest`. |
| `serviceHeaders` | `List<Pair<String, String>>` | HTTP headers specific to this service, sent with every request it makes. For auth headers shared across all services, use `DataBuoy.globalHeaderProvider` instead (see `docs/setup.md`). |

### SyncFetchConfig variants

Use `GetFetchConfig` when the server exposes a simple GET list endpoint:

```kotlin
SyncFetchConfig.GetFetchConfig(
    endpoint = "https://api.example.com/v2/items",
    syncCadenceSeconds = 300, // how often to poll
    transformResponse = { response ->
        val items = response["items"]?.jsonArray ?: return@GetFetchConfig emptyList()
        items.map { json.decodeFromJsonElement(YourModel.serializer(), it) }
    },
)
```

Use `PostFetchConfig` when fetching requires a request body (e.g., search/filter endpoints):

```kotlin
SyncFetchConfig.PostFetchConfig(
    endpoint = "https://api.example.com/v2/items/search",
    requestBody = buildJsonObject {
        put("filter_ids", buildJsonArray { add(JsonPrimitive("some-filter")) })
    },
    syncCadenceSeconds = 300,
    transformResponse = { response ->
        val items = response["items"]?.jsonArray ?: return@PostFetchConfig emptyList()
        items.map { json.decodeFromJsonElement(YourModel.serializer(), it) }
    },
)
```

### Template

```kotlin
class YourModelServerProcessingConfig : ServerProcessingConfig<YourModel> {

    private val json = Json { ignoreUnknownKeys = true }

    override val syncFetchConfig = SyncFetchConfig.GetFetchConfig(
        endpoint = "https://api.example.com/v2/items",
        syncCadenceSeconds = 300,
        transformResponse = { response ->
            val items = response["items"]?.jsonArray ?: return@GetFetchConfig emptyList()
            items.map { json.decodeFromJsonElement(YourModel.serializer(), it.jsonObject) }
        },
    )

    override val syncUpConfig = object : SyncUpConfig<YourModel>() {
        override fun fromResponseBody(requestTag: String, responseBody: JsonObject): SyncUpResult<YourModel> {
            val item = responseBody["item"]?.jsonObject ?: return SyncUpResult.Failed.RemovePendingRequest
            return SyncUpResult.Success(json.decodeFromJsonElement(YourModel.serializer(), item))
        }
    }

    override val serviceHeaders: List<Pair<String, String>> = listOf(
        Pair("Content-Type", "application/json"),
    )
}
```

### Builder API (recommended)

For most services, the builder API is the simplest way to create a config. It combines fetch, sync-up, and headers in one fluent call:

```kotlin
val unpacker = ResponseUnpacker.fromKey<YourModel>("item", YourModel.serializer())

val config = ServerProcessingConfig.builder<YourModel>()
    .fetchWithGet(
        endpoint = "https://api.example.com/v2/items",
        syncCadenceSeconds = 300,
    ) { response ->
        val items = response["items"]?.jsonArray ?: return@fetchWithGet emptyList()
        items.map { json.decodeFromJsonElement(YourModel.serializer(), it.jsonObject) }
    }
    .syncUpFromUnpacker(unpacker)  // reuses the same ResponseUnpacker
    .serviceHeaders("Content-Type" to "application/json")
    .build()
```

The `ResponseUnpacker.fromKey("item", serializer)` factory handles the common pattern where your API wraps responses in `{ "item": { ... } }`. You can reuse this same unpacker for both the config's sync-up and the service's synchronous operations (see Step 4).

If different request types need different parsing, use `syncUp { requestTag, responseBody -> ... }` instead of `syncUpFromUnpacker()`.

### Key rules

- `fromResponseBody()` receives the raw server response body and a `requestTag` string (from your `ServiceRequestTag` enum). Return `SyncUpResult.Success(data)` on success, `SyncUpResult.Failed.Retry` to re-queue the request, or `SyncUpResult.Failed.RemovePendingRequest` to drop it from the queue. Use the tag to handle different response shapes per request type if needed.
- The `transformResponse` lambda receives the full response body. You need to extract the array of items from whatever key your API nests them under.
- Override `SyncUpConfig.acceptUploadResponseAsProcessed()` only if you need custom retry logic. The default retries on 408 (timeout), 429 (rate limit), and 5xx errors.

---

## Step 4: Create the Service

Extend `SyncableObjectService<YourModel, YourModelRequestTag>` to expose your domain operations. The base class provides protected methods — `create()`, `update()`, `void()`, `get()`, and `resolveConflict()` — that handle the online/offline dual-path, local persistence, request queuing, and idempotency automatically. Your service wraps these into public methods with your domain-specific API logic.

There are also non-suspend flow-based variants — `createWithFlow()`, `updateWithFlow()`, `voidWithFlow()` — that launch the operation internally and return a `StateFlow<SyncableObjectServiceRequestState<O>>`. See [Flow-based operations](#flow-based-operations) below.

### Constructor

```kotlin
class YourModelService(
    serverProcessingConfig: ServerProcessingConfig<YourModel> = YourModelServerProcessingConfig(),
    connectivityChecker: ConnectivityChecker = createPlatformConnectivityChecker(),
    serverManager: ServerManager = ServerManager(
        serviceBaseHeaders = serverProcessingConfig.serviceHeaders,
    ),
    localStoreManager: LocalStoreManager<YourModel, YourModelRequestTag> = LocalStoreManager(
        codec = SyncCodec(YourModel.serializer()),
        serviceName = "your_model",
        syncScheduleNotifier = createPlatformSyncScheduleNotifier(),
    ),
) : SyncableObjectService<YourModel, YourModelRequestTag>(
    serializer = YourModel.serializer(),
    serverProcessingConfig = serverProcessingConfig,
    serviceName = "your_model",
    connectivityChecker = connectivityChecker,
    serverManager = serverManager,
    localStoreManager = localStoreManager,
) {
    // operations go here
}
```

The `serviceName` must be unique across all services in the app — it's used as a partition key in the shared SQLite database.

### Pending request queue strategy

When the device is offline (or a request times out), data-buoy queues pending requests in SQLite. The **queue strategy** controls how multiple pending requests for the same object are stored. You configure this via the `PendingRequestQueueManager.PendingRequestQueueStrategy` passed to `LocalStoreManager`.

There are two strategies:

| Strategy | Behavior |
|----------|----------|
| `Queue` (default) | Every create, update, and void is stored as a separate entry. Requests are replayed in order during sync-up. |
| `Squash` | Consecutive offline edits to the same object are collapsed into a single pending request. For example, a create followed by two updates becomes one create with the final state. |

**Use `Queue` when:**

- Your API is order-dependent or validates incremental version numbers on each request.
- Each request carries side effects the server must process individually (e.g., triggering notifications, audit logs, or webhooks per update).
- You want the simplest setup — `Queue` requires no extra configuration.

**Use `Squash` when:**

- Your API treats each write as a full replacement (PUT semantics) and intermediate states don't matter.
- You want to minimize network requests after a long offline session — a user who edits the same object 10 times offline will produce 1 sync-up request instead of 10.
- Reducing server-side version churn matters (e.g., the server increments a version on every write and you don't want to inflate it with intermediate states).

**Safety guard:** Squash never collapses a request that has already been attempted on the server (`serverAttemptMade = true`). If a request timed out and was re-queued, subsequent updates are stored as separate entries to avoid overwriting a request the server may have already processed.

#### Configuring Squash

To opt into squash, pass a `queueStrategy` to `LocalStoreManager`. You must provide a `SquashRequestMerger` — a `(createRequest, updateRequest) -> HttpRequest` function that merges an update request body into a pending create request:

```kotlin
val localStoreManager = LocalStoreManager<YourModel, YourModelRequestTag>(
    codec = SyncCodec(YourModel.serializer()),
    serviceName = "your_model",
    syncScheduleNotifier = createPlatformSyncScheduleNotifier(),
    queueStrategy = PendingRequestQueueManager.PendingRequestQueueStrategy.Squash(
        squashUpdateIntoCreate = SquashRequestMerger { createRequest, updateRequest ->
            // Replace the create body with the latest update body
            HttpRequest(
                method = createRequest.method,
                endpointUrl = createRequest.endpointUrl,
                requestBody = updateRequest.requestBody,
                additionalHeaders = createRequest.additionalHeaders,
            )
        },
    ),
)
```

For update-into-update squashing, the `UpdateRequestBuilder` you provide to `update()` is called with the previous pending data as `lastSyncedData` — the queue manager rebuilds the squashed request automatically. No extra configuration is needed beyond the `SquashRequestMerger` (which only handles the create-into-update case).

> **Note:** `LocalStoreManager` defaults to `Queue`. You only need to pass `queueStrategy` when opting into `Squash`.

### Create operation

Use the protected `create()` method. You provide:
- `data`: the new object
- `request`: a `CreateRequestBuilder` that builds the `HttpRequest` given the data, an idempotency key, whether the device is offline, and any prior attempted request
- `unpackSyncData`: a `ResponseUnpacker` that extracts the created object from the server response
- `requestTag`: your `ServiceRequestTag` value identifying this as a create operation
- `processingConstraints` (optional): `NoConstraints` (default), `OnlineOnly`, or `OfflineOnly`

```kotlin
suspend fun createItem(item: YourModel): CreateItemResponse {
    val response = create(
        data = item,
        requestTag = YourModelRequestTag.CREATE,
        request = CreateRequestBuilder { data, idempotencyKey, isOffline, attemptedServerRequest ->
            HttpRequest(
                method = HttpRequest.HttpMethod.POST,
                endpointUrl = "https://api.example.com/v2/items",
                requestBody = buildJsonObject {
                    put("idempotency_key", idempotencyKey)
                    put("name", data.name)
                    put("amount", data.amount)
                    put("reference_id", data.clientId)
                },
            )
        },
        unpackSyncData = ResponseUnpacker { responseBody, statusCode, syncStatus ->
            if (statusCode in 200..299 && responseBody.containsKey("item")) {
                json.decodeFromJsonElement(YourModel.serializer(), responseBody["item"]!!.jsonObject)
                    .withSyncStatus(syncStatus)
            } else null
        },
    )
    return when (response) {
        is SyncableObjectServiceResponse.Success.StoredLocally ->
            CreateItemResponse.Success(response.updatedData)

        is SyncableObjectServiceResponse.Success.NetworkResponseReceived ->
            CreateItemResponse.Success(response.updatedData!!)

        is SyncableObjectServiceResponse.Failed.NetworkResponseReceived ->
            CreateItemResponse.Failed(
                errors = response.responseBody["errors"]?.jsonArray ?: JsonArray(emptyList())
            )

        is SyncableObjectServiceResponse.ServerError ->
            CreateItemResponse.Failed(errors = JsonArray(emptyList()))

        is SyncableObjectServiceResponse.InvalidRequest,
        is SyncableObjectServiceResponse.NoInternetConnection,
        is SyncableObjectServiceResponse.Failed.LocalStoreFailed ->
            CreateItemResponse.Failed(errors = JsonArray(emptyList()))
    }
}
```

### Update operation

Use the protected `update()` method. The `UpdateRequestBuilder` receives both the last-synced version and the updated version, so you can compute a diff if your API supports partial updates.

Use `HttpRequest.SERVER_ID_PLACEHOLDER` (`{serverId}`) in endpoint URLs when the server ID might not be available yet (the object was created offline). Data-buoy resolves this placeholder at sync time.

Use `HttpRequest.VERSION_PLACEHOLDER` (`{version}`) in request bodies for optimistic concurrency version fields — also resolved at sync time.

```kotlin
suspend fun updateItem(item: YourModel, newName: String): SyncableObjectServiceResponse<YourModel> {
    val updatedItem = item.copy(name = newName)
    return update(
        data = updatedItem,
        requestTag = YourModelRequestTag.UPDATE,
        request = UpdateRequestBuilder { lastSyncedData, updatedData, idempotencyKey, isAsync, attemptedServerRequest ->
            HttpRequest(
                method = HttpRequest.HttpMethod.PUT,
                endpointUrl = "https://api.example.com/v2/items/${updatedData.serverId ?: HttpRequest.SERVER_ID_PLACEHOLDER}",
                requestBody = buildJsonObject {
                    put("idempotency_key", idempotencyKey)
                    put("name", updatedData.name)
                    put("version", HttpRequest.VERSION_PLACEHOLDER)
                },
            )
        },
        unpackSyncData = ResponseUnpacker { responseBody, statusCode, syncStatus ->
            if (statusCode in 200..299 && responseBody.containsKey("item")) {
                json.decodeFromJsonElement(YourModel.serializer(), responseBody["item"]!!.jsonObject)
                    .withSyncStatus(syncStatus)
            } else null
        },
    )
}
```

### Void (delete) operation

Use the protected `void()` method. If the object was never synced to the server (serverId is null and no server attempt was made), data-buoy skips the server call and just marks it voided locally.

The `VoidRequestBuilder` receives the data and a list of any pending requests that have already been attempted on the server.

```kotlin
suspend fun deleteItem(item: YourModel): SyncableObjectServiceResponse<YourModel> {
    return void(
        data = item,
        requestTag = YourModelRequestTag.VOID,
        request = VoidRequestBuilder { data, serverAttemptedPendingRequests ->
            HttpRequest(
                method = HttpRequest.HttpMethod.POST,
                endpointUrl = "https://api.example.com/v2/items/${data.serverId ?: HttpRequest.SERVER_ID_PLACEHOLDER}/cancel",
                requestBody = JsonObject(emptyMap()),
            )
        },
        unpackData = ResponseUnpacker { responseBody, statusCode, syncStatus ->
            if (statusCode in 200..299 && responseBody.containsKey("item")) {
                json.decodeFromJsonElement(YourModel.serializer(), responseBody["item"]!!.jsonObject)
                    .withSyncStatus(syncStatus)
            } else null
        },
    )
}
```

### Get operation

Use the protected `get()` method to fetch a single item. If the device is online and there are no pending requests for the object, the item is fetched from the server. Otherwise it's retrieved from the local store.

```kotlin
suspend fun getItem(clientId: String, serverId: String?): GetResponse<YourModel> {
    return get(
        clientId = clientId,
        serverId = serverId,
        request = HttpRequest(
            method = HttpRequest.HttpMethod.GET,
            endpointUrl = "https://api.example.com/v2/items/${serverId ?: HttpRequest.SERVER_ID_PLACEHOLDER}",
            requestBody = JsonObject(emptyMap()),
        ),
        unpackData = ResponseUnpacker { responseBody, statusCode, syncStatus ->
            if (statusCode in 200..299 && responseBody.containsKey("item")) {
                json.decodeFromJsonElement(YourModel.serializer(), responseBody["item"]!!.jsonObject)
                    .withSyncStatus(syncStatus)
            } else null
        },
    )
}
```

`GetResponse` is a sealed class with four variants:
- `ReceivedServerResponse(statusCode, responseBody, data)` — server responded
- `RetrievedFromLocalStore(data)` — returned from local SQLite
- `NoInternetConnection()` — not in local store and device is offline
- `NotFound()` — not in local store but device is online

### ResponseUnpacker convenience factories

Most REST APIs return data under a single key like `{ "item": { ... } }`. Instead of writing the same unpacker lambda for every operation, use the built-in factories:

```kotlin
// Single key — handles { "item": { ... } }
val unpacker = ResponseUnpacker.fromKey<YourModel>("item", YourModel.serializer())

// Multiple keys — tries each in order, useful when different operations
// return data under different keys
val unpacker = ResponseUnpacker.fromKeys<YourModel>(
    listOf("item", "order"),
    YourModel.serializer(),
)
```

These can be passed directly to `create()`, `update()`, `void()`, and `get()` as the `unpackSyncData`/`unpackData` parameter, and to `ServerProcessingConfig.Builder.syncUpFromUnpacker()` for sync-up.

### Fetching all local data

The base class provides `getAllFromLocalStore(limit: Int = 100)` to read all locally stored items. No need to override this.

### Reactive local data with Flow

For Compose or other reactive UIs, use `getAllFromLocalStoreAsFlow(limit: Int = 100)` instead. It returns a `Flow<List<O>>` that automatically emits whenever the underlying SQLite data changes (after sync-down, create, update, void, etc.):

```kotlin
// In a ViewModel:
val items: StateFlow<List<YourModel>> = yourModelService
    .getAllFromLocalStoreAsFlow()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
```

This eliminates the need for manual refresh calls after every operation.

### Processing constraints

Every `create()`, `update()`, and `void()` call accepts an optional `processingConstraints` parameter:

| Constraint | Behavior |
|------------|----------|
| `ProcessingConstraints.NoConstraints` | Default. Try online first, fall back to offline queuing on connection error. |
| `ProcessingConstraints.OnlineOnly` | Only attempt online. Returns `NoInternetConnection` on failure. |
| `ProcessingConstraints.OfflineOnly` | Skip the server call entirely. Always queue for background sync. |

### Flow-based operations

The base class also provides non-suspend wrappers — `createWithFlow()`, `updateWithFlow()`, and `voidWithFlow()` — that accept the same parameters as their suspend counterparts but return a `StateFlow<SyncableObjectServiceRequestState<O>>` instead. These launch the operation in the service's internal coroutine scope and emit state changes:

1. **`SyncableObjectServiceRequestState.Loading`** — emitted immediately when the flow is created
2. **`SyncableObjectServiceRequestState.Result(response)`** — emitted when the operation completes, wrapping the same `SyncableObjectServiceResponse<O>` you'd get from the suspend version

This is useful when callers don't have a coroutine scope readily available or when you want to expose a reactive API that UI layers can collect directly.

```kotlin
fun createItemFlow(item: YourModel): StateFlow<SyncableObjectServiceRequestState<YourModel>> {
    return createWithFlow(
        data = item,
        requestTag = YourModelRequestTag.CREATE,
        request = CreateRequestBuilder { data, idempotencyKey, isOffline, attemptedServerRequest ->
            HttpRequest(
                method = HttpRequest.HttpMethod.POST,
                endpointUrl = "https://api.example.com/v2/items",
                requestBody = buildJsonObject {
                    put("idempotency_key", idempotencyKey)
                    put("name", data.name)
                    put("amount", data.amount)
                    put("reference_id", data.clientId)
                },
            )
        },
        unpackSyncData = ResponseUnpacker { responseBody, statusCode, syncStatus ->
            if (statusCode in 200..299 && responseBody.containsKey("item")) {
                json.decodeFromJsonElement(YourModel.serializer(), responseBody["item"]!!.jsonObject)
                    .withSyncStatus(syncStatus)
            } else null
        },
    )
}
```

Collecting in a ViewModel:

```kotlin
val state = yourModelService.createItemFlow(item)

// In a composable or lifecycle-aware collector:
state.collect { requestState ->
    when (requestState) {
        is SyncableObjectServiceRequestState.Loading -> showSpinner()
        is SyncableObjectServiceRequestState.Result -> handleResponse(requestState.response)
    }
}
```

`updateWithFlow()` and `voidWithFlow()` follow the same pattern — same parameters as `update()` and `void()`, returning `StateFlow<SyncableObjectServiceRequestState<O>>`.

### Custom response types (optional)

It's a common pattern to define a sealed class for each operation's response to give callers a cleaner API:

```kotlin
sealed class CreateItemResponse {
    class Success(val item: YourModel) : CreateItemResponse()
    class Failed(val errors: JsonArray) : CreateItemResponse()
}
```

---

## Step 5: Register the Service

The service must be registered so that `SyncWorker` includes it in background sync. There are three approaches, from simplest to most manual.

### Option A: Hilt multibinding (recommended for Hilt apps)

If the consuming app uses Hilt and depends on the `data-buoy-hilt` artifact, registration is fully automatic. The consumer just provides `@IntoSet` bindings — no `Application.onCreate()` override or manual registration needed.

Add the dependency:

```kotlin
// build.gradle.kts
implementation("com.les.databuoy:data-buoy:<version>")
implementation("com.les.databuoy:data-buoy-hilt:<version>")
```

Then provide services' sync participants via a standard Hilt module:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object SyncModule {

    @Provides @IntoSet
    fun yourModelService(/* inject any deps */): SyncDriver<*, *> =
        YourModelService().syncDriver

    @Provides @IntoSet
    fun otherService(apiClient: ApiClient): SyncDriver<*, *> =
        OtherService(apiClient).syncDriver
}
```

### Option B: `DataBuoy.registerServices()` (simple, no Hilt required)

For apps that don't use Hilt, the `DataBuoy` object provides a one-liner registration API in `Application.onCreate()`:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DataBuoy.registerServices(setOf(yourModelService, otherService))
    }
}
```

Or for lazy/factory-based creation (participants are created fresh each time `SyncWorker` runs):

```kotlin
DataBuoy.registerServiceProvider(object : SyncServiceRegistryProvider {
    override fun createDrivers(context: Context) = listOf(
        YourModelService().syncDriver,
        OtherService().syncDriver,
    )
})
```

### Option C: Direct `SyncWorker.registerServiceProvider()` (lowest-level)

The most explicit approach — implement `SyncServiceRegistryProvider` and register it explicitly:

```kotlin
class AppSyncServiceRegistryProvider : SyncServiceRegistryProvider {
    override fun createDrivers(context: Context): List<SyncDriver<*, *>> = listOf(
        YourModelService().syncDriver,
        OtherService().syncDriver,
    )
}

// In Application.onCreate():
SyncWorker.registerServiceProvider(AppSyncServiceRegistryProvider())
```

### Using the service in UI code

Instantiate the service (typically with `by lazy`, or via Hilt `@Inject`) and call its methods from coroutine scopes:

```kotlin
private val yourModelService by lazy { YourModelService() }

// In a coroutine:
val response = yourModelService.createItem(item)
val allItems = yourModelService.getAllFromLocalStore()
yourModelService.close()  // call in onDestroy()
```

With Hilt, inject directly:

```kotlin
@HiltViewModel
class YourViewModel @Inject constructor(
    private val yourModelService: YourModelService,
) : ViewModel() {
    // ...
}
```

---

## SyncableObjectServiceResponse Reference

Every `create()`, `update()`, and `void()` call returns a `SyncableObjectServiceResponse<O>`. Handle all cases:

| Type | Meaning |
|------|---------|
| `Success.StoredLocally(updatedData)` | Device was offline; data saved locally and queued for background sync. |
| `Success.NetworkResponseReceived(statusCode, responseBody, updatedData)` | Server responded with a 2xx status code. |
| `Failed.NetworkResponseReceived(statusCode, responseBody)` | Server responded with a non-2xx, non-5xx status code. |
| `Failed.LocalStoreFailed(exception)` | SQLite write failed. |
| `NoInternetConnection` | Network call failed (connection error). |
| `InvalidRequest` | The object was in an invalid state for this operation (e.g., updating a voided item). |

---

## SyncableObjectServiceRequestState Reference

The flow-based wrappers (`createWithFlow()`, `updateWithFlow()`, `voidWithFlow()`) return a `StateFlow<SyncableObjectServiceRequestState<O>>` with these states:

| Type | Meaning |
|------|---------|
| `Loading` | The operation has been launched but has not completed yet. |
| `Result(response)` | The operation completed. `response` is a `SyncableObjectServiceResponse<O>` — handle it the same way as the suspend API. |

---

## SyncStatus Lifecycle

Understanding the sync status lifecycle helps when building UI that reflects sync state:

```
LocalOnly  ──create()──>  PendingCreate  ──background sync──>  Synced
                                                                  │
Synced  ──update()──>  PendingUpdate  ──background sync──>  Synced
                                                                  │
Synced  ──void()──>  PendingVoid  ──background sync──>  (removed)
                                                                  │
                              (server changed during pending)──>  Conflict
```

---

## HttpRequest.SERVER_ID_PLACEHOLDER and VERSION_PLACEHOLDER

When building requests for objects that might not have a `serverId` yet (created offline), use `HttpRequest.SERVER_ID_PLACEHOLDER` (`"{serverId}"`) in endpoint URLs and request bodies. Data-buoy replaces these with the real server ID at sync time, after the create request succeeds.

Similarly, use `HttpRequest.VERSION_PLACEHOLDER` (`"{version}"`) for optimistic concurrency version fields in request bodies.

```kotlin
// Endpoint with placeholder:
endpointUrl = "https://api.example.com/v2/items/${data.serverId ?: HttpRequest.SERVER_ID_PLACEHOLDER}"

// Body with version placeholder:
put("version", HttpRequest.VERSION_PLACEHOLDER)
```

---

## Conflict Resolution

When a sync-down detects that both the local client and server changed the same fields, the object enters `SyncStatus.Conflict`. The conflict contains `FieldConflictInfo` entries describing each conflicting field with base, local, and server values.

To resolve a conflict, call the protected `resolveConflict()` method with a `SyncableObjectRebaseHandler.ConflictResolution.Resolved` containing the merged data and optionally an updated HTTP request:

```kotlin
fun resolveItemConflict(
    resolvedItem: YourModel,
    updatedRequest: HttpRequest? = null,
): ResolveConflictResult<YourModel> {
    return resolveConflict(
        resolution = SyncableObjectRebaseHandler.ConflictResolution.Resolved(
            resolvedData = resolvedItem,
            updatedHttpRequest = updatedRequest,
        ),
    )
}
```

`ResolveConflictResult` has three variants:
- `Resolved(resolvedData)` — conflict cleared, sync-up can proceed
- `RebaseConflict(conflict)` — the immediate conflict was resolved but a subsequent pending request also has a conflict
- `Failed(exception)` — resolution failed

### Custom merge policies

Override the `rebaseHandler` property in your service to provide a custom `SyncableObjectRebaseHandler` with domain-specific merge logic:

```kotlin
override val rebaseHandler = object : SyncableObjectRebaseHandler<YourModel>(SyncCodec(YourModel.serializer())) {
    override fun handleMergeConflict(
        rebaseResult: RebaseResult<YourModel>,
        requestTag: String?,
    ): ConflictResolution<YourModel> {
        // Example: auto-resolve by always taking the server's value for "status"
        // Return ConflictResolution.Resolved(...) or ConflictResolution.Unresolved()
        return ConflictResolution.Unresolved()
    }
}
```

---

## Recommended file organization in the consuming app

```
app/src/main/java/com/example/yourapp/data/
├── models/
│   └── YourModel.kt                           # @Serializable SyncableObject implementation
├── services/customservices/yourmodel/
│   ├── YourModelService.kt                     # Service class
│   ├── YourModelServerProcessingConfig.kt      # ServerProcessingConfig
│   └── YourModelRequestTag.kt                  # ServiceRequestTag enum
└── di/
    └── SyncModule.kt                           # Hilt @Module with @IntoSet bindings
                                                # (or AppSyncServiceRegistryProvider if not using Hilt)
```

---

## Encryption at rest

data-buoy can optionally encrypt all persisted JSON blobs in SQLite on a per-service basis. Encryption is off by default; to enable it, implement `EncryptionProvider` and pass it to your service constructor.

### Implement `EncryptionProvider`

```kotlin
import com.les.databuoy.EncryptionProvider

class AesGcmEncryptionProvider(
    private val keyAlias: String,
) : EncryptionProvider {

    override fun encrypt(plaintext: String): String {
        // Your encryption logic here — e.g., AES-GCM via Android Keystore.
        // Return a string-safe representation (e.g., Base64-encoded ciphertext).
    }

    override fun decrypt(ciphertext: String): String {
        // Reverse the encryption.
    }
}
```

data-buoy is crypto-agnostic — use whatever algorithm and key management strategy your app requires (Android Keystore, Tink, Jetpack Security, etc.).

### Pass it to your service

```kotlin
class SecureItemService(
    encryptionProvider: EncryptionProvider = AesGcmEncryptionProvider("my-key-alias"),
    serverProcessingConfig: ServerProcessingConfig<SecureItem> = SecureItemServerProcessingConfig(),
    // ... other params ...
) : SyncableObjectService<SecureItem, SecureItemRequestTag>(
    serializer = SecureItem.serializer(),
    serverProcessingConfig = serverProcessingConfig,
    serviceName = "secure-items",
    encryptionProvider = encryptionProvider,
    // ... other params ...
)
```

### What gets encrypted

| Table | Encrypted columns |
|-------|------------------|
| `sync_data` | `data_blob`, `last_synced_server_data` |
| `sync_pending_events` | `data_blob`, `request`, `last_synced_data`, `conflict_info` |

Metadata columns (`service_name`, `client_id`, `server_id`, `sync_status`, `version`, etc.) remain plaintext because they are used in SQL queries and indexes.

### Key points

- **Per-service opt-in**: each service independently decides whether to encrypt. Encrypted and unencrypted services coexist in the same database.
- **In-memory objects are always plaintext**: encryption only applies at the SQLite storage boundary.
- **Key management is your responsibility**: data-buoy never touches cryptographic keys or primitives.
- **No schema changes required**: encrypted values are stored as TEXT (e.g., Base64-encoded ciphertext) in the same columns.

### Testing with encryption

`TestServiceEnvironment.createLocalStoreManager()` accepts an optional `encryptionProvider` parameter:

```kotlin
val env = TestServiceEnvironment()
val localStoreManager = env.createLocalStoreManager(
    codec = codec,
    serviceName = "secure-items",
    encryptionProvider = testEncryptionProvider,
)
```
