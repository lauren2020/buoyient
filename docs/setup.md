# Setting Up data-buoy in Your App

This guide walks through adding data-buoy to an Android app. After completing these steps, your app will have offline-first sync infrastructure ready for you to build services on top of.

**Prerequisites:** An Android app targeting API 27+ with Kotlin and `kotlinx.serialization` already configured.

---

## Step 1: Add Dependencies

Add the data-buoy artifacts to your app module's `build.gradle.kts`:

### Core library (required)

```kotlin
dependencies {
    implementation("com.les.databuoy:syncable-objects:<version>")
}
```

### Hilt integration (recommended if your app uses Hilt)

```kotlin
dependencies {
    implementation("com.les.databuoy:syncable-objects:<version>")
    implementation("com.les.databuoy:syncable-objects-hilt:<version>")
}
```

### Testing utilities

```kotlin
dependencies {
    testImplementation("com.les.databuoy:testing:<version>")

    // Also needed for mock mode in debug builds:
    debugImplementation("com.les.databuoy:testing:<version>")
}
```

Replace `<version>` with the current release version.

### Transitive dependencies

The `:syncable-objects` module exposes `kotlinx-serialization-json` as an `api` dependency — it appears transitively and you do **not** need to add it yourself unless you need a different version:

| Dependency | Purpose |
|-----------|---------|
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | JSON serialization (used in data-buoy's public API) |

Other internal dependencies (ktor, SQLDelight, coroutines, datetime, WorkManager, Startup) are declared as `implementation` and do **not** leak onto your classpath.

---

## Step 2: Automatic Initialization

data-buoy initializes itself automatically via `androidx.startup`. When your app starts:

1. `DataBuoyInitializer` captures the application context and sets up the platform layer
2. It triggers sync for any requests queued from previous sessions
3. `SyncWorker` is registered with WorkManager with a `NetworkType.CONNECTED` constraint

**You do not need to add anything to your `AndroidManifest.xml`** — the library's manifest declares the startup provider and it merges automatically.

### If your app disables automatic initialization

If your app disables `androidx.startup` auto-initialization (e.g., via manifest `tools:node="remove"`), you must manually initialize:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppInitializer.getInstance(this)
            .initializeComponent(DataBuoyInitializer::class.java)
    }
}
```

If using the Hilt module, also initialize `DataBuoyHiltInitializer`:

```kotlin
AppInitializer.getInstance(this)
    .initializeComponent(DataBuoyHiltInitializer::class.java)
```

---

## Step 3: Register Services

Before data-buoy can sync anything, you need to register your services for background sync. Choose the approach that fits your app:

> **Import note:** `DataBuoy`, `GlobalHeaderProvider`, and other project-level configuration classes live in the `com.les.databuoy.globalconfigs` package.

### Option A: Hilt multibinding (recommended for Hilt apps)

If you added the `syncable-objects-hilt` artifact, registration is fully automatic. Just provide `@IntoSet` bindings in a Hilt module:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object SyncModule {

    @Provides @IntoSet
    fun todoService(): SyncDriver<*, *> =
        TodoService().syncDriver

    @Provides @IntoSet
    fun noteService(apiClient: ApiClient): SyncDriver<*, *> =
        NoteService(apiClient).syncDriver
}
```

`DataBuoyHiltInitializer` (which runs after `DataBuoyInitializer`) registers a lazy provider that resolves these bindings when `SyncWorker` runs. No `Application.onCreate()` code needed.

### Option B: `DataBuoy.registerServices()` (no Hilt)

Register services directly in `Application.onCreate()`:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DataBuoy.registerServices(setOf(todoService, noteService))
    }
}
```

Or use a factory for lazy/fresh-per-sync-cycle creation:

```kotlin
DataBuoy.registerServiceProvider(object : SyncServiceRegistryProvider {
    override fun createDrivers(context: Context) = listOf(
        TodoService().syncDriver,
        NoteService().syncDriver,
    )
})
```

### Option C: Direct `SyncWorker.registerServiceProvider()` (lowest-level)

Implement `SyncServiceRegistryProvider` and register it explicitly:

```kotlin
class AppSyncServiceRegistryProvider : SyncServiceRegistryProvider {
    override fun createDrivers(context: Context): List<SyncDriver<*, *>> = listOf(
        TodoService().syncDriver,
        NoteService().syncDriver,
    )
}

// In Application.onCreate():
SyncWorker.registerServiceProvider(AppSyncServiceRegistryProvider())
```

---

## Step 3b: Configure Global Auth Headers (optional)

If all your services share the same auth headers (e.g., a bearer token), set a `GlobalHeaderProvider` once at startup instead of repeating the headers in every `ServerProcessingConfig`:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        DataBuoy.globalHeaderProvider = GlobalHeaderProvider {
            // Evaluated on every HTTP request — always reads the latest token.
            listOf("Authorization" to "Bearer ${authRepository.currentAccessToken}")
        }

        DataBuoy.registerServices(setOf(todoService, noteService))
    }
}
```

The provider is a lambda evaluated at request time, so refreshed tokens are picked up automatically — you never need to update the property after setting it.

At request time, headers are applied in this order:

1. **Global headers** — from `DataBuoy.globalHeaderProvider`
2. **Service headers** — from `ServerProcessingConfig.serviceHeaders`
3. **Request headers** — from `HttpRequest.additionalHeaders`

All three lists are concatenated and sent with every request. If the same header name appears in multiple lists, **both values are sent** (they are not deduplicated or overwritten). To avoid unexpected behavior, don't set the same header name in more than one list — for example, set `Authorization` in the global provider only, not also in `serviceHeaders`.

Use `globalHeaderProvider` for headers shared across all services (e.g., auth tokens). Use `serviceHeaders` for headers unique to a single service (e.g., a service-specific API version or API key). Use `additionalHeaders` on individual `HttpRequest` objects for one-off per-request headers.

---

## Step 4: Verify the Setup

After adding dependencies and registering at least one service, verify everything is wired correctly:

1. **Build the app** — confirm no dependency resolution errors
2. **Launch the app** — check logcat for `DataBuoyInitializer` log output confirming initialization
3. **Create an item** — call `service.create(item)` and verify it returns a `SyncableObjectServiceResponse`
4. **Check local persistence** — call `service.getAllFromLocalStore()` and confirm the item is stored
5. **Check background sync** — if the device has network connectivity, the item should sync automatically within a few seconds
6. **Trigger an on-demand sync** — call `DataBuoy.syncNow()` (e.g. from a pull-to-refresh handler) to force an immediate sync-up pass without waiting for the background scheduler:
   ```kotlin
   // In a pull-to-refresh handler:
   DataBuoy.syncNow { success ->
       swipeRefreshLayout.isRefreshing = false
   }
   ```

---

## What's Next

With the library set up, you're ready to build services:

- **Create a service** — see `docs/creating-a-service.md` for the complete walkthrough of data models, request tags, server configs, and service classes
- **Write tests** — see `docs/integration-testing.md` for automated JVM tests using `TestServiceEnvironment`
- **Set up mock mode** — see `docs/mock-mode.md` for running the app against fake responses without a real backend

---

## Recommended project structure

```
app/src/main/java/com/example/yourapp/
├── MyApp.kt                                    # Application class (if not using Hilt)
├── data/
│   ├── models/
│   │   ├── Todo.kt                             # @Serializable SyncableObject
│   │   └── Note.kt
│   ├── services/customservices/
│   │   ├── todo/
│   │   │   ├── TodoService.kt
│   │   │   ├── TodoServerProcessingConfig.kt
│   │   │   └── TodoRequestTag.kt
│   │   └── note/
│   │       ├── NoteService.kt
│   │       ├── NoteServerProcessingConfig.kt
│   │       └── NoteRequestTag.kt
│   └── di/
│       └── SyncModule.kt                       # Hilt @IntoSet bindings (or SyncServiceRegistryProvider)
```

---

## Troubleshooting

### "No services registered" warning in logcat
Your services aren't being discovered by `SyncWorker`. Ensure you've completed Step 3 above — either Hilt `@IntoSet` bindings, `DataBuoy.registerServices()`, or `SyncWorker.registerServiceProvider()`.

### Dependency conflict with Ktor / SQLDelight
If your app already uses Ktor or SQLDelight, ensure version compatibility. Check data-buoy's `gradle/libs.versions.toml` for the exact versions used (e.g. Ktor and SQLDelight). You can force-resolve versions in your `build.gradle.kts`:

```kotlin
configurations.all {
    resolutionStrategy {
        force("io.ktor:ktor-client-core:2.3.13")
    }
}
```

### Items not syncing in the background
- Confirm `SyncWorker` is scheduled by checking WorkManager status in logcat
- Ensure the device has network connectivity (WorkManager uses a `NetworkType.CONNECTED` constraint)
- Verify that `SyncFetchConfig` and `SyncUpConfig` are correctly configured in your `ServerProcessingConfig`
