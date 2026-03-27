# Setting Up Local Mock Mode for Manual Testing

This guide covers how to configure a buoyient app to run against a mock server at runtime, so developers can manually test the app without a real backend. This uses the same `MockEndpointRouter` from the `:testing` module but wired into the live app's dependency graph behind a developer toggle.

---

## Dependencies

Add the testing module as a regular `implementation` dependency (not `testImplementation`) in the app module:

```kotlin
// app/build.gradle.kts
implementation(project(":testing"))
// or, if consuming as a published artifact:
implementation("com.les.buoyient:testing:<version>")
```

**Important:** You may want to scope this to debug builds only to keep it out of production:

```kotlin
debugImplementation(project(":testing"))
```

If using `debugImplementation`, the mock mode code must live in `src/debug/` or be gated behind a `BuildConfig` check.

---

## Architecture Overview

The mock mode works by setting `Buoyient.httpClient` to a mock-backed HTTP client before creating any services. All services constructed after this point automatically route requests through the mock handlers. No changes to your service classes are needed.

```
Normal mode:
  SyncableObjectService ---> ServerManager ---> real Ktor HttpClient ---> real server

Mock mode:
  Buoyient.httpClient = mockRouter.buildHttpClient()
  SyncableObjectService ---> ServerManager ---> MockEndpointRouter ---> mock handlers
```

---

## Step 1: Define Your Mock Fixtures

Create a class that configures the `MockEndpointRouter` with realistic fake responses for all of your service's endpoints. This is the heart of mock mode -- the handlers define what fake data the app operates on.

```kotlin
package com.example.yourapp.testing

import com.les.buoyient.datatypes.HttpRequest
import com.les.buoyient.testing.MockEndpointRouter
import com.les.buoyient.testing.MockResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
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
                        ?: JsonPrimitive(UUID.randomUUID().toString()))
                    put("name", request.body["name"]
                        ?: JsonPrimitive("Untitled"))
                    put("amount", request.body["amount"]
                        ?: JsonPrimitive(0))
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
- **Include all fields** the `SyncUpConfig.fromResponseBody()` expects -- missing fields will cause deserialization to return `SyncUpResult.Failed.RemovePendingRequest()` and the service will drop the pending request.
- **Match your real API's response shape exactly** -- the same `transformResponse` and `fromResponseBody` lambdas parse both real and mock responses.

---

## Step 2: Create a Mock Mode Toggle

Set `Buoyient.httpClient` before creating any services. All services constructed after this point automatically route requests through the mock handlers — no per-service wiring needed.

### Option A: Hilt-based toggle (recommended for Hilt apps)

```kotlin
package com.example.yourapp.di

import com.les.buoyient.globalconfigs.Buoyient
import com.les.buoyient.utils.BuoyientLog
import com.les.buoyient.testing.PrintSyncLogger
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
        if (!flag.enabled) return null
        val fixtures = MockServerFixtures()
        // Install the mock HTTP client globally — all services pick it up automatically
        Buoyient.httpClient = fixtures.router.buildHttpClient()
        BuoyientLog.logger = PrintSyncLogger  // verbose logging for mock mode
        return fixtures
    }
}

data class MockModeFlag(val enabled: Boolean)
```

Services are provided normally — no mock-specific constructor params needed:

```kotlin
@Provides
@IntoSet
fun provideItemService(): SyncableObjectService<*, *> = YourModelService()
```

### Option B: Simple factory (no Hilt)

```kotlin
object ServiceFactory {

    private val mockFixtures by lazy { MockServerFixtures() }

    fun enableMockMode() {
        Buoyient.httpClient = mockFixtures.router.buildHttpClient()
        BuoyientLog.logger = PrintSyncLogger
    }

    fun createItemService(): YourModelService = YourModelService()
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
        BuoyientPlatformContext.appContext.getSharedPreferences("dev_settings", Context.MODE_PRIVATE)
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

Return data from the sync-down endpoint that conflicts with locally modified data. The `SyncableObjectRebaseHandler` will detect the field-level conflicts and mark the row with `SyncStatus.Conflict`, letting you test your conflict resolution UI:

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
- **`BuoyientLog.logger = PrintSyncLogger` is recommended** in mock mode so developers can see sync engine activity in Logcat. Set this once at startup before creating any services.
- **`MockEndpointRouter` is thread-safe.** The request log uses `CopyOnWriteArrayList` and the route list is only written during setup.

---

## Recommended file organization

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
