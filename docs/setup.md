# Setting Up data-buoy in Your App

This guide walks through adding data-buoy to an Android app. After completing these steps, your app will have offline-first sync infrastructure ready for you to build services on top of.

**Prerequisites:** An Android app targeting API 24+ with Kotlin and `kotlinx.serialization` already configured.

---

## Step 1: Add Dependencies

Add the data-buoy artifacts to your app module's `build.gradle.kts`:

### Core library (required)

```kotlin
dependencies {
    implementation("com.les.databuoy:library:<version>")
}
```

### Hilt integration (recommended if your app uses Hilt)

```kotlin
dependencies {
    implementation("com.les.databuoy:library:<version>")
    implementation("com.les.databuoy:data-buoy-hilt:<version>")
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

The `:library` module brings in these dependencies transitively — you do **not** need to add them yourself unless you need a different version:

| Dependency | Version | Purpose |
|-----------|---------|---------|
| `io.ktor:ktor-client-okhttp` | 2.3.13 | HTTP client (Android) |
| `app.cash.sqldelight:android-driver` | 2.0.2 | Local SQLite storage |
| `androidx.work:work-runtime-ktx` | 2.10.0 | Background sync scheduling |
| `androidx.startup:startup-runtime` | 1.2.0 | Automatic initialization |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.8.1 | Coroutine support |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.6.3 | JSON serialization |
| `org.jetbrains.kotlinx:kotlinx-datetime` | 0.6.2 | Date/time handling |

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

Before data-buoy can sync anything, you need to register your `SyncableObjectService` implementations. Choose the approach that fits your app:

### Option A: Hilt multibinding (recommended for Hilt apps)

If you added the `data-buoy-hilt` artifact, registration is fully automatic. Just provide `@IntoSet` bindings in a Hilt module:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object SyncModule {

    @Provides @IntoSet
    fun todoService(): SyncableObjectService<*, *> =
        TodoService()

    @Provides @IntoSet
    fun noteService(apiClient: ApiClient): SyncableObjectService<*, *> =
        NoteService(apiClient)
}
```

`DataBuoyHiltInitializer` (which runs after `DataBuoyInitializer`) registers a lazy provider that resolves these bindings when `SyncWorker` runs. No `Application.onCreate()` code needed.

### Option B: `DataBuoy.registerServices()` (no Hilt)

Register services directly in `Application.onCreate()`:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DataBuoy.registerServices(setOf(
            TodoService(),
            NoteService(),
        ))
    }
}
```

Or use a factory for lazy/fresh-per-sync-cycle creation:

```kotlin
DataBuoy.registerServiceProvider(object : SyncServiceRegistryProvider {
    override fun createServices(context: Context) = listOf(
        TodoService(),
        NoteService(),
    )
})
```

### Option C: Direct `SyncWorker.registerServiceProvider()` (lowest-level)

Implement `SyncServiceRegistryProvider` and register it explicitly:

```kotlin
class AppSyncServiceRegistryProvider : SyncServiceRegistryProvider {
    override fun createServices(context: Context): List<SyncableObjectService<*, *>> = listOf(
        TodoService(),
        NoteService(),
    )
}

// In Application.onCreate():
SyncWorker.registerServiceProvider(AppSyncServiceRegistryProvider())
```

---

## Step 4: Verify the Setup

After adding dependencies and registering at least one service, verify everything is wired correctly:

1. **Build the app** — confirm no dependency resolution errors
2. **Launch the app** — check logcat for `DataBuoyInitializer` log output confirming initialization
3. **Create an item** — call `service.create(item)` and verify it returns a `SyncableObjectServiceResponse`
4. **Check local persistence** — call `service.getAllFromLocalStore()` and confirm the item is stored
5. **Check background sync** — if the device has network connectivity, the item should sync automatically within a few seconds

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
If your app already uses Ktor or SQLDelight, ensure version compatibility. data-buoy uses Ktor 2.3.13 and SQLDelight 2.0.2. You can force-resolve versions in your `build.gradle.kts`:

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
