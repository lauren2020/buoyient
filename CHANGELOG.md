# Changelog

All notable changes to data-buoy will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - Unreleased

### Added

- Pagination and filtering support via the new `:paging` module (`com.elvdev.buoyient:syncable-objects-paging`, Android only).
- `BuoyientPagingSource<O, T>` — `PagingSource<PageCursor, O>` for Jetpack Paging 3 with optional `Filter` predicates, sync-status constraints, and auto-invalidation on local store changes.
- `PagingConfig<O>` — per-service pagination configuration (paging key via `keyExtractor`, sort order via `SortOrder`). Override `pagingConfig` on `SyncableObjectService` to customize.
- `loadPage(afterCursor, loadSize, syncStatus, filter)` — keyset cursor pagination on `SyncableObjectService`, available on all platforms (KMP). Returns `PageResult<O>`.
- `PageCursor` — opaque keyset cursor; `null` starts from the beginning.
- `PageResult<O>` — one page of results: `items: List<O>` and `nextCursor: PageCursor?`.
- `Filter` — sealed predicate for filtered local-store queries. Factories: `eq`, `ne`, `gt`, `gte`, `lt`, `lte`, `in`, `like`, `isNull`, `isNotNull`, `and`, `or`, `not`.
- `indexedJsonPaths` — override on `SyncableObjectService` to declare JSON path expressions backed by SQLite expression indexes for efficient filtered queries.

## [0.1.0] - Unreleased

### Added
- Core offline-first sync engine with bidirectional sync between local SQLite and a remote server.
- `SyncableObject` interface and `SyncableObjectService` base class for building syncable data types.
- Automatic offline request queuing with `Queue` and `Squash` pending request strategies.
- `SyncFetchConfig` for periodic sync-down (GET or POST).
- `SyncUpConfig` with `fromResponseBody()` for flexible sync-up response parsing.
- `SyncableObjectRebaseHandler` for 3-way merge conflict detection and resolution.
- Cross-service dependency resolution via `HttpRequest.crossServiceServerIdPlaceholder()`.
- Optional encryption at rest via `EncryptionProvider`.
- Dynamic global headers via `GlobalHeaderProvider`.
- Flow-based operation variants (`createWithFlow`, `updateWithFlow`, `voidWithFlow`).
- `:hilt` module — optional Hilt integration with `@IntoSet` multibinding for automatic service registration.
- `:testing` module — `TestServiceEnvironment`, `MockEndpointRouter`, and `MockServerStore` for JVM integration tests and mock mode.
- Comprehensive documentation in `docs/` covering setup, service creation, integration testing, and mock mode.
