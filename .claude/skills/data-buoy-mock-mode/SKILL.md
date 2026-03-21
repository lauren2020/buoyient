---
name: data-buoy-mock-mode
description: "How to set up local mock mode for manual testing of apps that use data-buoy, so the app runs against fake server responses without a real backend. Use this skill when the user wants to run their app with mock data, set up a developer toggle for fake/real server, create a local testing mode, build a demo mode, configure mock responses at runtime, or test the app manually without a backend. Also trigger when the user mentions mock mode, fake server, demo mode, local testing mode, or developer toggle in the context of data-buoy or SyncableObjectService."
---

# Setting Up Local Mock Mode for Manual Testing

This skill covers how to configure a data-buoy app to run against a mock server at runtime, so developers can manually test the app without a real backend. This uses the same `MockEndpointRouter` from the `:testing` module but wired into the live app's dependency graph behind a developer toggle.

---

## Dependencies

Add the testing module as a regular `implementation` dependency (not `testImplementation`) in the app module:

```kotlin
// app/build.gradle.kts
implementation(project(":testing"))
// or, if consuming as a published artifact:
implementation("com.les.databuoy:testing:<version>")
```

**Important:** You may want to scope this to debug builds only to keep it out of production:

```kotlin
debugImplementation(project(":testing"))
```

If using `debugImplementation`, the mock mode code must live in `src/debug/` or be gated behind a `BuildConfig` check.

---

## Architecture Overview

The mock mode works by swapping the `ServerManager` (and optionally `ConnectivityChecker`) that gets passed to your `SyncableObjectService` constructors. No changes to the core library or your service classes are needed -- `SyncableObjectService` already accepts these as constructor parameters with defaults.

```
Normal mode:
  SyncableObjectService ---> ServerManager ---> real Ktor HttpClient ---> real server

Mock mode:
  SyncableObjectService ---> ServerManager ---> MockEndpointRouter ---> mock handlers
```

---

## Step 1: Define Your Mock Fixtures

Create a class that configures the `MockEndpointRouter` with realistic fake responses for all of your service's endpoints. This is the heart of mock mode -- the handlers define what fake data the app operates on.

```kotlin
package com.example.yourapp.testing

import com.example.sync.HttpRequest
import com.example.sync.testing.MockEndpointRouter
import com.example.sync.testing.MockResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class MockServerFixtures {

    val router = MockEndpointRouter()
    private val idCounter = AtomicInteger(0)

    init {
        setupItemEndpoints()
        // Add more: setupOrderEndpoints(), setupUserEndpoints(), etc.
    }

    private fun setupItemEndpoints() {
        // GET /items -- sync-down fetch
        router.onGet("https://api.example.com/v2/items") { _ ->
            MockResponse(200, buildJsonObject {
                put("items", buildJsonArray {
                    add(buildJsonObject {
                        put("id", "mock-srv-1")
                        put("reference_id", "mock-client-1")
                        put("name", "Sample Item A")
                        put("amount", 1500)
                        put("status", "ACTIVE")
                        put("version", 1)
                    })
                    add(buildJsonObject {
                        put("id", "mock-srv-2")
                        put("reference_id", "mock-client-2")
                        put("name", "Sample Item B")
                        put("amount", 2500)
                        put("status", "DRAFT")
                        put("version", 1)
                    })
                })
            })
        }

        // POST /items -- create
        router.onPost("https://api.example.com/v2/items") { request ->
            val newId = "mock-srv-${idCounter.incrementAndGet()}"
            MockResponse(201, buildJsonObject {
                put("item", buildJsonObject {
                    put("id", newId)
                    put("reference_id", request.body["reference_id"]
                        ?: kotlinx.serialization.json.JsonPrimitive(UUID.randomUUID().toString()))
                    put("name", request.body["name"]
                        ?: kotlinx.serialization.json.JsonPrimitive("Untitled"))
                    put("amount", request.body["amount"]
                        ?: kotlinx.serialization.json.JsonPrimitive(0))
                    put("status", "ACTIVE")
                    put("version", 1)
                })
            })
        }

        // PUT /items/{id} -- update
        router.onPut("https://api.example.com/v2/items/*") { request ->
            MockResponse(200, buildJsonObject {
                put("item", buildJsonObject {
                    // Echo back the request fields as the "updated" server response
                    request.body.forEach { (key, value) ->
                        put(key, value)
                    }
                    put("version", 2)
                })
            })
        }

        // DELETE or POST /items/{id}/cancel -- void
        router.onDelete("https://api.example.com/v2/items/*") { _ ->
            MockResponse(200, buildJsonObject {
                put("item", buildJsonObject {
                    put("status", "CANCELLED")
                })
            })
        }
    }
}
```

### Tips for realistic fixtures

- **Echo request data back** in create/update responses so the app sees its own data reflected.
- **Generate unique server IDs** with an `AtomicInteger` counter so each create returns a distinct ID.
- **Include all fields** the `ServerProcessingConfig.syncUpConfig.fromResponseBody()` expects -- missing fields will cause null deserialization and the service will treat it as a failed sync.
- **Match your real API's response shape exactly** -- the same `transformResponse` and `fromResponseBody` lambdas parse both real and mock responses.

---

## Step 2: Create a Mock Mode Toggle

### Option A: Hilt-based toggle (recommended for Hilt apps)

Create a `@Module` that conditionally provides mock or real dependencies based on a runtime flag:

```kotlin
package com.example.yourapp.di

import com.example.sync.ConnectivityChecker
import com.example.sync.ServerManager
import com.example.sync.SyncLogger
import com.example.sync.testing.MockEndpointRouter
import com.example.sync.testing.PrintSyncLogger
import com.example.sync.testing.TestConnectivityChecker
import com.example.yourapp.testing.MockServerFixtures
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MockModeModule {

    @Provides
    @Singleton
    fun provideMockModeEnabled(): MockModeFlag {
        // Read from SharedPreferences, BuildConfig, or developer settings
        return MockModeFlag(enabled = BuildConfig.DEBUG && MockModePrefs.isEnabled)
    }

    @Provides
    @Singleton
    fun provideMockFixtures(flag: MockModeFlag): MockServerFixtures? {
        return if (flag.enabled) MockServerFixtures() else null
    }

    @Provides
    @Singleton
    fun provideServerManagerForItems(
        flag: MockModeFlag,
        fixtures: MockServerFixtures?,
        logger: SyncLogger,
    ): ItemServiceServerManager {
        return if (flag.enabled && fixtures != null) {
            ItemServiceServerManager(
                fixtures.router.buildServerManager(logger = logger)
            )
        } else {
            ItemServiceServerManager(
                ServerManager(
                    serviceBaseHeaders = RealApiConfig.headers,
                    logger = logger,
                )
            )
        }
    }

    @Provides
    @Singleton
    fun provideConnectivityChecker(flag: MockModeFlag): ConnectivityChecker {
        return if (flag.enabled) {
            // In mock mode, always report online so requests go through the mock
            TestConnectivityChecker(online = true)
        } else {
            createPlatformConnectivityChecker()
        }
    }

    @Provides
    @Singleton
    fun provideLogger(flag: MockModeFlag): SyncLogger {
        return if (flag.enabled) PrintSyncLogger else createPlatformSyncLogger()
    }
}

data class MockModeFlag(val enabled: Boolean)

// Type wrapper to distinguish from other ServerManager instances in Hilt graph
data class ItemServiceServerManager(val serverManager: ServerManager)
```

Then inject into your service:

```kotlin
@Provides
@IntoSet
fun provideItemService(
    sm: ItemServiceServerManager,
    connectivity: ConnectivityChecker,
    logger: SyncLogger,
): SyncableObjectService<*, *> = YourModelService(
    serverProcessingConfig = YourModelServerProcessingConfig(),
    serverManager = sm.serverManager,
    connectivityChecker = connectivity,
    logger = logger,
)
```

### Option B: Simple factory (no Hilt)

```kotlin
object ServiceFactory {

    var mockModeEnabled: Boolean = false

    private val mockFixtures by lazy { MockServerFixtures() }

    fun createItemService(): YourModelService {
        val config = YourModelServerProcessingConfig()
        return if (mockModeEnabled) {
            YourModelService(
                serverProcessingConfig = config,
                serverManager = mockFixtures.router.buildServerManager(
                    serviceBaseHeaders = config.globalHeaders,
                    logger = PrintSyncLogger,
                ),
                connectivityChecker = TestConnectivityChecker(online = true),
                logger = PrintSyncLogger,
            )
        } else {
            YourModelService(serverProcessingConfig = config)
        }
    }
}
```

Toggle in `Application.onCreate()` or from a developer settings screen:

```kotlin
ServiceFactory.mockModeEnabled = true
```

---

## Step 3: Add a Developer Settings UI (Optional)

A simple toggle in your settings screen:

```kotlin
// In a SettingsActivity or debug drawer:
val mockModeSwitch = findView<Switch>(R.id.mock_mode_switch)
mockModeSwitch.isChecked = MockModePrefs.isEnabled
mockModeSwitch.setOnCheckedChangeListener { _, isChecked ->
    MockModePrefs.isEnabled = isChecked
    // Restart the app or recreate services for the change to take effect
    Toast.makeText(this, "Mock mode ${if (isChecked) "enabled" else "disabled"}. Restart app.", Toast.LENGTH_SHORT).show()
}
```

Where `MockModePrefs` is a simple SharedPreferences wrapper:

```kotlin
object MockModePrefs {
    private const val KEY = "mock_mode_enabled"

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY, false)
        set(value) = prefs.edit().putBoolean(KEY, value).apply()

    private val prefs by lazy {
        DataBuoyPlatformContext.appContext.getSharedPreferences("dev_settings", Context.MODE_PRIVATE)
    }
}
```

---

## Step 4: Simulating Specific Scenarios

### Simulating offline mode

Use `TestConnectivityChecker` to force the offline path, even though the device has network:

```kotlin
val connectivity = TestConnectivityChecker(online = false)
// Service will queue all requests locally instead of sending to mock server
```

This is useful for testing the pending queue UI or offline indicators.

### Simulating server errors

Return error status codes from mock handlers:

```kotlin
router.onPost("https://api.example.com/v2/items") { _ ->
    MockResponse(500, buildJsonObject {
        put("error", "Internal Server Error")
    })
}
```

### Simulating slow responses or timeouts

Throw `MockConnectionException` to simulate a timeout/connection failure:

```kotlin
router.onPost("https://api.example.com/v2/items") { _ ->
    throw MockConnectionException("Simulated timeout")
}
```

### Simulating sync conflicts

Return data from the sync-down endpoint that conflicts with locally modified data. The `SyncableObjectMergeHandler` will detect the field-level conflicts and mark the row with `SyncStatus.Conflict`, letting you test your conflict resolution UI:

```kotlin
router.onGet("https://api.example.com/v2/items") { _ ->
    MockResponse(200, buildJsonObject {
        put("items", buildJsonArray {
            add(buildJsonObject {
                // Return a version where "name" differs from the local pending update
                put("id", "srv-1")
                put("reference_id", "c1")
                put("name", "Server Changed Name")  // conflicts with local edit
                put("version", 2)
            })
        })
    })
}
```

### Inspecting mock traffic

Use `router.requestLog` to see what the app sent during a manual test session:

```kotlin
// In a debug drawer or log dump:
fixtures.router.requestLog.forEachIndexed { i, req ->
    Log.d("MockMode", "#$i ${req.method} ${req.url} body=${req.body}")
}
```

---

## Using MockServerStore for Stateful Mock Mode

Instead of hand-writing static responses for every endpoint, use `MockServerStore` to back your mock handlers with persistent server-side state. CREATEs actually create records, GETs return real data, and the app behaves much more realistically during manual testing.

### Replacing MockServerFixtures with a Store-Backed Approach

```kotlin
class MockServerFixtures {

    val router = MockEndpointRouter()
    val store = MockServerStore()

    init {
        setupItemEndpoints()
    }

    private fun setupItemEndpoints() {
        val items = store.collection("items")

        // Seed some initial data
        items.seed("mock-srv-1", buildJsonObject {
            put("name", "Sample Item A")
            put("amount", 1500)
            put("status", "ACTIVE")
        }, version = 1, clientId = "mock-client-1")

        items.seed("mock-srv-2", buildJsonObject {
            put("name", "Sample Item B")
            put("amount", 2500)
            put("status", "DRAFT")
        }, version = 1, clientId = "mock-client-2")

        // Wire automatic CRUD handlers — replaces all the manual onGet/onPost/onPut/onDelete
        router.registerCrudHandlers(
            collection = items,
            baseUrl = "https://api.example.com/v2/items",
            responseWrapper = { record ->
                buildJsonObject { put("item", record.toJsonObject()) }
            },
            listResponseWrapper = { records ->
                buildJsonObject {
                    put("items", JsonArray(records.map { it.toJsonObject() }))
                }
            },
        )
    }
}
```

This replaces the manual handler code from Step 1 with a single `registerCrudHandlers` call. The store handles ID generation, version tracking, and data persistence automatically.

### Benefits for Manual Testing

- **Realistic round-trips**: Create an item in the app, then navigate to the list view and see it there — because it's actually stored.
- **Multi-device simulation**: Open a debug console or developer settings UI that calls `store.collection("items").mutate(...)` to simulate another device's edit, then pull-to-refresh.
- **Sync conflict demo**: Modify a record via the store while the app has a local pending update, then trigger sync to see the conflict resolution UI in action.

### Exposing the Store in Developer Settings

```kotlin
// In a debug-only developer tools screen:
val items = fixtures.store.collection("items")
Button("Add random server item") {
    items.create(buildJsonObject {
        put("name", "Random ${System.currentTimeMillis()}")
        put("amount", (100..9999).random())
    })
}
Button("Mutate first item") {
    val first = items.getAll().firstOrNull()
    if (first != null) {
        items.mutate(first.serverId) { data ->
            buildJsonObject {
                data.forEach { (k, v) -> put(k, v) }
                put("name", "Mutated at ${System.currentTimeMillis()}")
            }
        }
    }
}
Text("Server records: ${items.count()}")
```

---

## Important Rules

- **Mock mode uses the same database as real mode** (the normal Android SQLite database). If you switch between mock and real mode, previously synced data persists. Clear the database on mode switch if you want a fresh start.
- **`TestConnectivityChecker` should be set to `online = true`** in mock mode so requests actually flow through the mock server. Setting it to `false` queues requests for background sync, which is fine for testing offline behavior but means you won't see immediate mock responses.
- **The `SyncScheduleNotifier` should remain the real platform implementation** in mock mode (not the no-op). This way, background sync (WorkManager) still fires and processes the pending queue through the mock server, giving a realistic experience.
- **Mock handlers are evaluated at request time**, not registration time. You can update handlers dynamically during a session.
- **`PrintSyncLogger` is recommended** in mock mode so developers can see sync engine activity in Logcat, making it easier to understand what's happening.
- **`MockEndpointRouter` is thread-safe.** The request log uses `CopyOnWriteArrayList` and the route list is only written during setup.

---

## File Organization

```
app/src/
├── main/java/com/example/yourapp/
│   ├── data/services/           # Service classes (unchanged)
│   └── di/                      # DI modules
│       └── MockModeModule.kt    # Conditional mock/real provider
├── debug/java/com/example/yourapp/
│   └── testing/
│       ├── MockServerFixtures.kt    # Mock endpoint definitions
│       └── MockModePrefs.kt         # SharedPreferences toggle
└── debug/res/
    └── xml/
        └── developer_settings.xml   # Optional debug settings UI
```

Placing mock fixtures under `src/debug/` ensures they are stripped from release builds automatically.
