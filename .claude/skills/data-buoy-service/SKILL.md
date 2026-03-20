---
name: data-buoy-service
description: "How to create and register new SyncableObjectService implementations using the data-buoy offline-first sync library. Use this skill whenever the user wants to add a new data type or service to an app that uses data-buoy, create a syncable model, build CRUD operations with offline support, set up background sync for a new entity, or integrate a new API endpoint with data-buoy's sync engine. Also trigger when the user mentions SyncableObject, SyncableObjectService, ServerProcessingConfig, or asks about offline-first data patterns in a Kotlin/Android context."
---

# Creating a data-buoy Service

This skill walks through creating a complete offline-first syncable service using data-buoy. A service consists of four pieces that work together:

1. **Data model** — a Kotlin class implementing `SyncableObject<T>` that defines your domain entity
2. **Server processing config** — a `ServerProcessingConfig<T>` that tells data-buoy how to communicate with your API
3. **Service class** — a `SyncableObjectService<T>` subclass that exposes create/update/void operations
4. **Registration** — adding the service to `AppSyncServiceRegistryProvider` so background sync picks it up

The library handles online/offline dual-path execution, local SQLite persistence, pending request queuing, idempotent retries, background sync via WorkManager, and 3-way merge conflict resolution automatically. Your job is just to define the shape of your data and how to talk to your API.

**Important: use the user's API, not the examples.** The existing services in the codebase (PaymentService, OrderService) talk to a specific Square sandbox API. When creating a new service, use the endpoints, auth headers, field names, and base URLs that the user specifies — do not copy Square-specific values like `connect.squareupsandbox.com`, the Square Bearer token, `location_id: "LD0K6CFYP3DP7"`, or Square-Version headers into your new service. The existing services are useful as structural references for *how* to use data-buoy's APIs, but the concrete URLs, credentials, and field mappings must come from the user's requirements.

---

## Step 1: Create the Data Model

Create a class that implements `SyncableObject<YourModel>`. This is the domain object that data-buoy persists and syncs.

### Required interface members

Every `SyncableObject` must have:

| Field | Type | Purpose |
|-------|------|---------|
| `serverId` | `String?` | Server's ID. `null` until first sync. |
| `clientId` | `String` | Locally-generated UUID. Stable identifier regardless of sync state. |
| `version` | `Int` | Incremented on each update. Used for optimistic concurrency. |
| `syncStatus` | `SyncStatus` | Tracks sync lifecycle (LocalOnly, PendingCreate, Synced, Conflict, etc.) |
| `toJson()` | `JsonObject` | Serializes the object for local SQLite storage. |

### Required companion members

| Member | Purpose |
|--------|---------|
| `fromJson(json, syncStatus)` | Deserializes from SQLite JSON blob back into your model. |
| Field tag constants | String constants for each JSON key, used in `toJson()` and `fromJson()`. |

### Template

```kotlin
package com.example.yourapp.data.models

import com.example.sync.SyncableObject
import com.example.sync.SyncableObject.Companion.CLIENT_ID_TAG
import com.example.sync.SyncableObject.Companion.SERVER_ID_TAG
import com.example.sync.SyncableObject.Companion.VERSION_TAG
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.UUID

class YourModel(
    override val serverId: String?,
    override val clientId: String,
    override val version: Int,
    override val syncStatus: SyncableObject.SyncStatus,
    // Add your domain-specific fields here:
    val name: String,
    val amount: Long,
    val status: String,
) : SyncableObject<YourModel> {

    /**
     * Convenience constructor for creating a new local-only instance.
     * Sets serverId=null, generates a UUID clientId, version=0, syncStatus=LocalOnly.
     * Fields with a natural initial state get sensible defaults (e.g., status).
     */
    constructor(
        name: String,
        amount: Long,
    ) : this(
        serverId = null,
        clientId = UUID.randomUUID().toString(),
        version = 0,
        syncStatus = SyncableObject.SyncStatus.LocalOnly,
        name = name,
        amount = amount,
        status = "DRAFT", // sensible default — callers don't need to specify initial state
    )

    override fun toJson(): JsonObject = buildJsonObject {
        put(SERVER_ID_TAG, serverId)
        put(CLIENT_ID_TAG, clientId)
        put(VERSION_TAG, version)
        put(NAME_TAG, name)
        put(AMOUNT_TAG, amount)
        put(STATUS_TAG, status)
    }

    companion object {
        const val NAME_TAG = "name"
        const val AMOUNT_TAG = "amount"
        const val STATUS_TAG = "status"

        fun fromJson(json: JsonObject, syncStatus: SyncableObject.SyncStatus): YourModel = YourModel(
            serverId = json[SERVER_ID_TAG]?.jsonPrimitive?.contentOrNull,
            clientId = json[CLIENT_ID_TAG]!!.jsonPrimitive.content,
            version = json[VERSION_TAG]!!.jsonPrimitive.int,
            syncStatus = syncStatus,
            name = json[NAME_TAG]!!.jsonPrimitive.content,
            amount = json[AMOUNT_TAG]!!.jsonPrimitive.long,
            status = json[STATUS_TAG]!!.jsonPrimitive.content,
        )
    }
}
```

### Key rules

- `toJson()` must include `SERVER_ID_TAG`, `CLIENT_ID_TAG`, and `VERSION_TAG` — data-buoy reads these from the JSON blob.
- `fromJson()` must accept a `syncStatus` parameter (data-buoy manages sync status separately from the blob).
- The convenience constructor should set `serverId = null`, generate a random `clientId`, set `version = 0`, and set `syncStatus = SyncableObject.SyncStatus.LocalOnly`. For fields that have a natural initial state (like `status`), provide a sensible default in the convenience constructor rather than requiring the caller to pass it. For example, an order might default to `state = "DRAFT"`, a ticket to `status = "OPEN"`, a payment to `status = "PENDING"`.
- The tag constants you define should match whatever key names your server API uses for those fields, since `fromServerProtoJson()` in the config will map server keys to these tags.

---

## Step 2: Create the ServerProcessingConfig

Implement `ServerProcessingConfig<YourModel>` to tell data-buoy how to fetch data from and push data to your server.

### Required members

| Member | Type | Purpose |
|--------|------|---------|
| `syncFetchConfig` | `SyncFetchConfig<T>` | How to periodically pull data from the server (GET or POST). |
| `syncUpConfig` | `SyncUpConfig` | Controls retry behavior for failed uploads. Default is usually fine. |
| `headers` | `List<Pair<String, String>>` | HTTP headers sent with every request (auth, content-type, etc.). |
| `fromServerProtoJson(json)` | `T` | Converts a single server JSON object into your domain model. |

### SyncFetchConfig variants

Use `GetFetchConfig` when the server exposes a simple GET list endpoint:

```kotlin
SyncFetchConfig.GetFetchConfig(
    endpoint = "https://api.example.com/v2/items",
    syncCadenceSeconds = 300, // how often to poll
    transformResponse = { response ->
        val items = response["items"]?.jsonArray ?: return@GetFetchConfig emptyList()
        items.map { fromServerProtoJson(it.jsonObject) }
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
        items.map { fromServerProtoJson(it.jsonObject) }
    },
)
```

### Template

```kotlin
class YourModelServerProcessingConfig : ServerProcessingConfig<YourModel> {
    override val syncFetchConfig = SyncFetchConfig.GetFetchConfig(
        endpoint = "https://api.example.com/v2/items",
        syncCadenceSeconds = 300,
        transformResponse = { response ->
            val items = response["items"]?.jsonArray ?: return@GetFetchConfig emptyList()
            items.map { fromServerProtoJson(it.jsonObject) }
        },
    )

    override val syncUpConfig: SyncUpConfig = SyncUpConfig()

    override val headers: List<Pair<String, String>> = listOf(
        Pair("Authorization", "Bearer YOUR_TOKEN"),
        Pair("Content-Type", "application/json"),
    )

    override fun fromServerProtoJson(json: JsonObject): YourModel = YourModel(
        serverId = json["id"]!!.jsonPrimitive.content,
        clientId = json["reference_id"]?.jsonPrimitive?.contentOrNull
            ?: json["id"]!!.jsonPrimitive.content,
        version = json["version"]?.jsonPrimitive?.int ?: 1,
        syncStatus = SyncableObject.SyncStatus.Synced(
            lastSyncedTimestamp = json["updated_at"]!!.jsonPrimitive.content,
        ),
        name = json["name"]!!.jsonPrimitive.content,
        amount = json["amount"]!!.jsonPrimitive.long,
        status = json["status"]?.jsonPrimitive?.content ?: "DRAFT",
    )
}
```

### Key rules

- `fromServerProtoJson()` must always set `syncStatus` to `Synced` with a timestamp — this data is coming from the server, so it's by definition synced.
- Map `clientId` to whatever field links server records back to client-created records (often `reference_id`). Fall back to the server `id` for records created server-side.
- The `transformResponse` lambda receives the full response body. You need to extract the array of items from whatever key your API nests them under.
- Override `SyncUpConfig` only if you need custom retry logic. The default retries on 408 (timeout) and 5xx errors.

---

## Step 3: Create the Service

Extend `SyncableObjectService<YourModel>` to expose your domain operations. The base class provides three protected methods — `create()`, `update()`, and `void()` — that handle the online/offline dual-path, local persistence, request queuing, and idempotency automatically. Your service wraps these into public methods with your domain-specific API logic.

### Constructor

```kotlin
class YourModelService : SyncableObjectService<YourModel>(
    serviceName = "your_model",  // unique name, used as DB partition key
    deserializer = object : SyncableObject.SyncableObjectDeserializer<YourModel> {
        override fun fromJson(json: JsonObject, syncStatus: SyncableObject.SyncStatus): YourModel =
            YourModel.fromJson(json, syncStatus)
    },
    serverProcessingConfig = YourModelServerProcessingConfig(),
) {
    // operations go here
}
```

The `serviceName` must be unique across all services in the app — it's used as a partition key in the shared SQLite database.

### Create operation

Use the protected `create()` method. You provide:
- `data`: the new object
- `request`: a lambda that builds the `HttpRequest` given the data, an idempotency key, and whether the device is offline
- `unpackSyncData`: a lambda that extracts the created object from the server response

```kotlin
suspend fun createItem(item: YourModel): CreateItemResponse {
    val response = create(
        data = item,
        request = { data, idempotencyKey, isOffline ->
            HttpRequest(
                method = HttpRequest.HttpMethod.POST,
                endpointUrl = "https://api.example.com/v2/items",
                requestBody = buildJsonObject {
                    put("idempotency_key", idempotencyKey)
                    put("name", data.name)
                    put("amount", data.amount)
                    put("reference_id", data.clientId)
                },
                responseDataUnwrapPath = listOf("item"),
            )
        },
        unpackSyncData = { status, response ->
            if (response.containsKey("item")) {
                serverProcessingConfig.fromServerProtoJson(response["item"]!!.jsonObject)
            } else null
        },
    )
    return when (response) {
        is SyncableObjectServiceResponse.Finished.StoredLocally ->
            CreateItemResponse.Success(response.updatedData)

        is SyncableObjectServiceResponse.Finished.NetworkResponseReceived -> {
            if (response.statusCode in 200..203) {
                val item = serverProcessingConfig.fromServerProtoJson(
                    response.responseBody["item"]!!.jsonObject
                )
                CreateItemResponse.Success(item)
            } else {
                CreateItemResponse.Failed(
                    errors = response.responseBody["errors"]?.jsonArray ?: JsonArray(emptyList())
                )
            }
        }

        is SyncableObjectServiceResponse.InvalidRequest,
        is SyncableObjectServiceResponse.NoInternetConnection,
        is SyncableObjectServiceResponse.LocalStoreFailed ->
            CreateItemResponse.Failed(errors = JsonArray(emptyList()))
    }
}
```

### Update operation

Use the protected `update()` method. The `request` lambda receives both the last-synced version and the updated version, so you can compute a diff if your API supports partial updates.

Use `HttpRequest.SERVER_ID_PLACEHOLDER` (`{serverId}`) in endpoint URLs when the server ID might not be available yet (the object was created offline). Data-buoy resolves this placeholder at sync time.

Use `HttpRequest.VERSION_PLACEHOLDER` (`{version}`) in request bodies for optimistic concurrency version fields — also resolved at sync time.

```kotlin
suspend fun updateItem(item: YourModel, newName: String): SyncableObjectServiceResponse<YourModel> {
    val updatedItem = YourModel(
        serverId = item.serverId,
        clientId = item.clientId,
        version = item.version,
        syncStatus = item.syncStatus,
        name = newName,
        amount = item.amount,
    )
    return update(
        data = updatedItem,
        request = { lastSyncedData, updatedData, idempotencyKey ->
            HttpRequest(
                method = HttpRequest.HttpMethod.PUT,
                endpointUrl = "https://api.example.com/v2/items/${updatedData.serverId ?: HttpRequest.SERVER_ID_PLACEHOLDER}",
                requestBody = buildJsonObject {
                    put("idempotency_key", idempotencyKey)
                    put("name", updatedData.name)
                    put("version", HttpRequest.VERSION_PLACEHOLDER)
                },
                responseDataUnwrapPath = listOf("item"),
            )
        },
        unpackSyncData = { responseBody, statusCode, syncStatus ->
            if (responseBody.containsKey("item")) {
                val serverItem = serverProcessingConfig.fromServerProtoJson(
                    responseBody["item"]!!.jsonObject
                )
                YourModel(
                    serverId = serverItem.serverId,
                    clientId = serverItem.clientId,
                    version = serverItem.version,
                    syncStatus = syncStatus,  // use the syncStatus passed in, not the one from parsing
                    name = serverItem.name,
                    amount = serverItem.amount,
                )
            } else null
        },
    )
}
```

### Void (delete) operation

Use the protected `void()` method. If the object was never synced to the server (serverId is null and no server attempt was made), data-buoy skips the server call and just marks it voided locally.

```kotlin
suspend fun deleteItem(item: YourModel) {
    void(
        data = item,
        request = { data, serverAttemptedPendingRequests ->
            HttpRequest(
                method = HttpRequest.HttpMethod.POST,
                endpointUrl = "https://api.example.com/v2/items/${data.serverId ?: HttpRequest.SERVER_ID_PLACEHOLDER}/cancel",
                requestBody = JsonObject(emptyMap()),
                responseDataUnwrapPath = listOf("item"),
            )
        },
        unpackData = { status, response ->
            if (response.containsKey("item")) {
                serverProcessingConfig.fromServerProtoJson(response["item"]!!.jsonObject)
            } else null
        },
    )
}
```

### Fetching local data

The base class provides `fetchFromDb(limit: Int = 100)` to read all locally stored items. No need to override this.

### Custom response types (optional)

It's a common pattern to define a sealed class for each operation's response to give callers a cleaner API:

```kotlin
sealed class CreateItemResponse {
    class Success(val item: YourModel) : CreateItemResponse()
    class Failed(val errors: JsonArray) : CreateItemResponse()
}
```

---

## Step 4: Register the Service

Add your new service to the app's `SyncServiceRegistryProvider` implementation so that `SyncWorker` includes it in background sync.

```kotlin
class AppSyncServiceRegistryProvider : SyncServiceRegistryProvider {
    override fun createServices(context: Context): List<SyncableObjectService<*>> = listOf(
        PaymentService(),
        OrderService(),
        YourModelService(),  // add your new service here
    )
}
```

This provider is registered once in `MainActivity.onCreate()`:

```kotlin
SyncWorker.registerServiceProvider(AppSyncServiceRegistryProvider())
```

### Using the service in UI code

Instantiate the service (typically with `by lazy`) and call its methods from coroutine scopes:

```kotlin
private val yourModelService by lazy { YourModelService() }

// In a coroutine:
val response = yourModelService.createItem(item)
val allItems = yourModelService.fetchFromDb()
yourModelService.close()  // call in onDestroy()
```

---

## SyncableObjectServiceResponse Reference

Every `create()`, `update()`, and `void()` call returns a `SyncableObjectServiceResponse<O>`. Handle all cases:

| Type | Meaning |
|------|---------|
| `Finished.StoredLocally(updatedData)` | Device was offline; data saved locally and queued for background sync. |
| `Finished.NetworkResponseReceived(statusCode, responseBody, updatedData)` | Server responded. Check `statusCode` for success/failure. |
| `NoInternetConnection` | Network call failed (connection error). |
| `InvalidRequest` | The object was in an invalid state for this operation (e.g., updating a voided item). |
| `LocalStoreFailed(exception)` | SQLite write failed. |

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

## File Organization

Follow this directory structure within the app module:

```
app/src/main/java/com/example/yourapp/data/
├── models/
│   └── YourModel.kt                           # SyncableObject implementation
├── services/customservices/yourmodel/
│   └── YourModelService.kt                     # Service + ServerProcessingConfig
└── sdk/
    └── AppSyncServiceRegistryProvider.kt        # Service registration
```

The `ServerProcessingConfig` class is typically defined in the same file as the service class.
