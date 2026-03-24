---
name: data-buoy-mock-mode
description: "How to set up local mock mode for manual testing of apps that use data-buoy, so the app runs against fake server responses without a real backend. Use this skill when the user wants to run their app with mock data, set up a developer toggle for fake/real server, create a local testing mode, build a demo mode, configure mock responses at runtime, or test the app manually without a backend. Also trigger when the user mentions mock mode, fake server, demo mode, local testing mode, or developer toggle in the context of data-buoy or SyncableObjectService."
---

Read the guide at `docs/mock-mode.md` for the complete, up-to-date instructions on setting up local mock mode for manual testing. That file is the authoritative reference and is kept in sync with the actual codebase.

Key points to remember:
- Mock mode works by swapping the `ServerManager` passed to `SyncableObjectService` constructors — no changes to core library or service classes needed.
- Use `MockEndpointRouter` from the `:testing` module to register mock HTTP handlers.
- Use `MockServerStore` for stateful mock mode with realistic CRUD behavior.
- `TestConnectivityChecker` should be set to `online = true` in mock mode.
- Conflict simulation uses `SyncableObjectRebaseHandler` (not `SyncableObjectMergeHandler`).
- Include all fields that `SyncUpConfig.fromResponseBody()` expects in mock responses — missing fields will cause it to return `SyncUpResult.Failed.RemovePendingRequest`.
- Scope the `:testing` dependency to debug builds with `debugImplementation`.
