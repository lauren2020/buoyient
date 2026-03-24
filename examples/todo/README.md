# Todo Example

This example is the intentionally minimal "golden path" service for data-buoy.

Use it together with the starter files in `templates/` when adding a new service:

1. Copy the model, request tag, server config, service, and test templates.
2. Replace the Todo-specific names, endpoints, headers, and response paths with your API.
3. Register the service in your app using the patterns in `docs/creating-a-service.md`.
4. Keep the integration test shape: build a `TestServiceEnvironment`, register mock routes, construct the service, and assert both online and offline behavior.

The source lives under:

- `examples/todo/src/main/kotlin/com/les/databuoy/examples/todo/`
- `examples/todo/src/test/kotlin/com/les/databuoy/examples/todo/`

Those files are compiled as part of `:library`'s JVM tests so API drift shows up in CI.
