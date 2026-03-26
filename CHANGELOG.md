# Changelog

All notable changes to data-buoy will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - Unreleased

### Added
- Core offline-first sync engine with bidirectional sync between local SQLite and a remote server.
- `SyncableObject` interface and `SyncableObjectService` base class for building syncable data types.
- Automatic offline request queuing with `Queue` and `Squash` pending request strategies.
- `SyncFetchConfig` for periodic sync-down (GET or POST).
- `SyncUpConfig` with `fromResponseBody()` for flexible sync-up response parsing.
- `SyncableObjectRebaseHandler` for 3-way merge conflict detection and resolution.
- Cross-service dependency resolution via `HttpRequest.crossServicePlaceholder()`.
- Optional encryption at rest via `EncryptionProvider`.
- Dynamic global headers via `GlobalHeaderProvider`.
- Flow-based operation variants (`createWithFlow`, `updateWithFlow`, `voidWithFlow`).
- `:hilt` module — optional Hilt integration with `@IntoSet` multibinding for automatic service registration.
- `:testing` module — `TestServiceEnvironment`, `MockEndpointRouter`, and `MockServerStore` for JVM integration tests and mock mode.
- Comprehensive documentation in `docs/` covering setup, service creation, integration testing, and mock mode.
