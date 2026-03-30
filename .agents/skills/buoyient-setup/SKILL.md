---
name: buoyient-setup
description: "How to set up and install the buoyient offline-first sync library in an Android app. Use this skill when the user wants to add buoyient to their project, configure dependencies, set up initialization, register services for background sync, or integrate buoyient into an existing Android app. Also trigger when the user mentions adding buoyient as a dependency, setting up offline sync, configuring WorkManager for sync, or asks about BuoyientInitializer, Buoyient.registerServices, or SyncWorker setup."
---

Use `docs/setup.md` as the authoritative setup guide, and the project-level context file (`CLAUDE.md` / `CODEX.md`) for a fast project-level overview.

Key points to remember:
- The `:syncable-objects` artifact (`com.elvdev.buoyient:syncable-objects`) is the only required dependency. Add `:syncable-objects-hilt` for Hilt apps, `:testing` for automated tests, `:mock-mode` for manual mock testing.
- Initialization is automatic via `androidx.startup` — no manifest changes or `Application.onCreate()` code needed for the library itself.
- Services must be registered so `SyncWorker` can include them in background sync. Three approaches: Hilt `@IntoSet` multibinding (recommended), `Buoyient.registerServices()`, or `SyncWorker.registerServiceProvider()`.
- Project-level configuration classes (`Buoyient`, `GlobalHeaderProvider`, `DatabaseProvider`, etc.) are in the `com.elvdev.buoyient.globalconfigs` package.
- `BuoyientHiltInitializer` registers a lazy provider that resolves Hilt bindings when `SyncWorker` runs — no eager initialization.
- Transitive dependencies (Ktor, SQLDelight, WorkManager, kotlinx.serialization) are pulled in automatically.
- After setup, proceed to `docs/creating-a-service.md` to build your first service.
