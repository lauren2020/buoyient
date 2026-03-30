---
name: buoyient-mock-mode
description: "How to set up local mock mode for manual testing of apps that use buoyient, so the app runs against fake server responses without a real backend. Use this skill when the user wants to run their app with mock data, set up a developer toggle for fake/real server, create a local testing mode, build a demo mode, configure mock responses at runtime, or test the app manually without a backend. Also trigger when the user mentions mock mode, fake server, demo mode, local testing mode, or developer toggle in the context of buoyient or SyncableObjectService."
---

Use `docs/mock-mode.md` as the authoritative guide, and the project-level context file (`CLAUDE.md` / `CODEX.md`) for shared project conventions.

Key points to remember:
- Mock mode works by setting `Buoyient.httpClient` (in `com.elvdev.buoyient.globalconfigs`) to a mock-backed HTTP client before creating any services — no changes to service classes needed.
- Use `MockEndpointRouter` from the `:mock-mode` module to register mock HTTP handlers.
- Use `MockServerStore` for stateful mock mode with realistic CRUD behavior.
- `TestConnectivityChecker` should be set to `online = true` in mock mode.
- Conflict simulation uses `SyncableObjectRebaseHandler` (not `SyncableObjectMergeHandler`).
- Include all fields that `SyncUpConfig.fromResponseBody()` expects in mock responses — missing fields will cause it to return `SyncUpResult.Failed.RemovePendingRequest`.
- Scope the `:mock-mode` dependency to debug builds with `debugImplementation`.
