---
name: data-buoy-setup
description: "How to set up and install the data-buoy offline-first sync library in an Android app. Use this skill when the user wants to add data-buoy to their project, configure dependencies, set up initialization, register services for background sync, or integrate data-buoy into an existing Android app. Also trigger when the user mentions adding data-buoy as a dependency, setting up offline sync, configuring WorkManager for sync, or asks about DataBuoyInitializer, DataBuoy.registerServices, or SyncWorker setup."
---

Read the guide at `docs/setup.md` for the complete, up-to-date instructions on setting up data-buoy in a consuming app. That file is the authoritative reference and is kept in sync with the actual codebase.

Key points to remember:
- The `:library` artifact (`com.les.databuoy:library`) is the only required dependency. Add `:data-buoy-hilt` for Hilt apps, `:testing` for tests and mock mode.
- Initialization is automatic via `androidx.startup` — no manifest changes or `Application.onCreate()` code needed for the library itself.
- Services must be registered so `SyncWorker` can include them in background sync. Three approaches: Hilt `@IntoSet` multibinding (recommended), `DataBuoy.registerServices()`, or `SyncWorker.registerServiceProvider()`.
- `DataBuoyHiltInitializer` registers a lazy provider that resolves Hilt bindings when `SyncWorker` runs — no eager initialization.
- Transitive dependencies (Ktor, SQLDelight, WorkManager, kotlinx.serialization) are pulled in automatically.
- After setup, proceed to `docs/creating-a-service.md` to build your first service.
