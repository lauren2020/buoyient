# Pagination & Filtering — Design Plan (Retroactive)

---
**Status:** ✅ Implemented

**Target:** buoyient 0.2.0

**Last Updated:** 2026-04-29

---

## Overview

Adds two related capabilities to `SyncableObjectService`:

1. **Keyset cursor pagination** — `loadPage(...)` returns one page at a time using a `(paging_key, client_id)` cursor, with consumer-configurable sort key and direction.
2. **Filter queries** — `loadPage(filter = ...)` accepts a `Filter` predicate over `data_blob` JSON fields, composing cleanly with pagination, sort order, and sync-status filtering.

Two delivery vehicles round out the feature:
- A new Android-only `:paging` module ships `BuoyientPagingSource<O, T>`, a `PagingSource<PageCursor, O>` adapter for Jetpack Paging 3.
- A new `:paging_key` column on `sync_data` (with a SQLDelight migration) backs the cursor; expression indexes on `data_blob` JSON paths back the filter.

Existing API (`getAllFromLocalStore`, `getFromLocalStore`) is unchanged — pagination and filtering are additive.

---

## Goals

- **Make pagination work out of the box.** Every service should be paginatable with no extra configuration; consumers who care about a specific sort key opt in to override.
- **Allow consumers to choose their own paging key.** Different services have different "natural" sort fields (timestamps, names, sequence numbers); the design must not hardcode any one of them.
- **Support filtering on arbitrary `data_blob` fields.** No fixed projection list — any field is queryable, with opt-in indexing for hot paths.
- **Compose filter + cursor + sync_status + sort order without combinatorial explosion** in the implementation.
- **Stay KMP-clean.** The core `:syncable-objects` module must not depend on `androidx.paging`; that lives in a separate Android-only module.

## Non-goals

- A typed-paths DSL (`Item.Path.status` instead of `"$.status"`). Out of scope for V1 — string-typed paths are simple and cover the common case.
- Generated columns or shadow projection columns for filterable fields. Considered and rejected (see § Filter implementation).
- Aggregate queries (`COUNT`, `SUM`, `GROUP BY`) against `data_blob`. Out of scope.
<!-- Filter support inside BuoyientPagingSource is now implemented. Reverse pagination is now implemented — see § 12. -->

- Built-in support for *dynamic* filter swapping inside a single `PagingSource` instance. Filters are bound at construction; consumers swap filters by reconstructing the source (the canonical Paging 3 pattern, achievable in one `flatMapLatest` over a filter `Flow` — see `BuoyientPagingSource` KDoc).

---

## Public API surface

| Type / call | Module / package | Purpose |
|---|---|---|
| `PagingConfig<O>(keyExtractor, sortOrder)` | `serviceconfigs` | Per-service pagination configuration; defaults to `keyExtractor = { it.clientId }`, `sortOrder = DESC` |
| `PagingConfig.SortOrder { ASC, DESC }` | `serviceconfigs` | Sort direction |
| `PageCursor(key, clientId)` | `datatypes` | Opaque cursor — composite of paging key + clientId tiebreaker |
| `PageResult<O>(items, nextCursor, prevCursor)` | `datatypes` | One page of items with `nextCursor` (null at tail) and `prevCursor` (null at head) |
| `PageDirection { FromHead, Forward(afterCursor), Backward(beforeCursor) }` | `datatypes` | Sealed interface — selects which page to load relative to a cursor; sealed shape makes "backward from nothing" unrepresentable |
| `Filter` (sealed interface + companion factories) | `datatypes` | `Eq`, `Ne`, `Gt`, `Gte`, `Lt`, `Lte`, `In`, `Like`, `IsNull`, `IsNotNull`, `And`, `Or`, `Not` |
| `SyncableObjectService.pagingConfig: PagingConfig<O>` | top-level | Constructor param; non-null with sensible default |
| `SyncableObjectService.indexedJsonPaths: List<String>` | top-level | Constructor param; declares JSON paths to back with SQLite expression indexes |
| `SyncableObjectService.loadPage(direction, loadSize, syncStatus, filter)` | top-level | Returns `PageResult<O>`. `direction` defaults to `PageDirection.FromHead`. |
| `BuoyientPagingSource<O, T>(service, syncStatus, filter)` | `:paging` module | Jetpack Paging 3 adapter |

---

## Design decisions

### 1. Keyset cursor over OFFSET / LIMIT

**Decision:** Pages are addressed by a cursor (`PageCursor`), not an integer offset.

**Why:**
- OFFSET on a large table forces SQLite to scan and discard `OFFSET` rows on every page request. O(n) per page; O(n²) over a full scroll.
- Keyset reduces each page fetch to a B-tree seek + sequential read of `loadSize` rows.
- Insert/delete during scroll doesn't shift the cursor — OFFSET would silently skip or duplicate rows.

**Bidirectional via mirrored predicate.** Reverse pagination (§ 12) is supported by flipping the cursor comparison and `ORDER BY` direction, then reversing the resulting list in Kotlin so callers always see items in the configured sort order.

### 2. Dedicated `paging_key` column over inline `json_extract` for ordering

**Decision:** Added a `paging_key TEXT` column to `sync_data`, populated on every write via the `pagingConfig.keyExtractor` lambda. The `ORDER BY` and cursor-comparison clauses operate on this column.

**Alternatives considered:**

- **Inline `json_extract(data_blob, '$.field')`** — works for filtering but creates two problems for sort: (a) requires SQLite JSON1, which isn't guaranteed at all SQLite versions buoyient targets; (b) every paginated query becomes a full scan unless an expression index is created, and that index would be tied to a fixed JSON path rather than a per-service consumer choice.
- **Generated columns** (`paging_key TEXT GENERATED ALWAYS AS (json_extract(data_blob, '$.created_at'))`) — same JSON1 dependency, plus the path is fixed in the schema instead of per-service.

**Why a stored column wins:** Single SQL/index strategy works on every SQLite version, ordering doesn't require JSON1, indexes are simple B-trees on a known column, and per-service `keyExtractor` lambdas don't need to encode JSON paths.

**Lexicographic-ordering footgun:** Since the column is `TEXT`, comparisons are lexicographic. Documented in `PagingConfig`'s KDoc with concrete examples (ISO 8601 timestamps ✓, `"%010d".format(int)` ✓, raw `Int.toString()` ✗ because `"10" < "2"`). A typed `PagingKey` sealed type with `String` and `Long` variants was considered but deferred — string formatting solves all common cases without adding API surface.

### 3. Composite cursor `(paging_key, client_id)` with `client_id` as tiebreaker

**Decision:** The cursor is a tuple of `(paging_key, client_id)`, not a single `paging_key` value. The cursor predicate is:

```sql
paging_key > :cursor_key
  OR (paging_key = :cursor_key AND client_id > :cursor_client_id)
```

(With `<` for DESC.)

**Why:** Paging keys can collide. Two items with identical `created_at` timestamps (second-level resolution + bulk insert) would otherwise produce undefined ordering and a `paging_key > X` cursor would skip rows tied at `X` after the cursor row.

The `client_id` tiebreaker:
- Is always non-null and unique per service (it's a primary-key component on `sync_data`).
- Is already indexed via the table's primary key.
- Has no extra storage cost.

When a consumer picks `paging_key = clientId` (the default), the tiebreaker clause is provably never true, and the query reduces to a simple `client_id > X` — no skipped or duplicated rows.

### 4. DESC as the default sort order

**Decision:** `PagingConfig.sortOrder` defaults to `DESC`.

**Why:** The most common paging key in offline-first sync apps is a server-assigned timestamp; the most common UI affordance is "most recent first." DESC matches that. Consumers who want "oldest first" or alphabetical pass `SortOrder.ASC`.

### 5. Non-null `pagingConfig` with `clientId`-based default

**Decision:** `pagingConfig` is non-null. The default is `PagingConfig(keyExtractor = { it.clientId })`, so `loadPage` works on any service without explicit configuration.

**Why:** A nullable `pagingConfig` forced every consumer wanting pagination to write boilerplate, and `loadPage` to start every call with a `checkNotNull(...)`. With every `SyncableObject` already having a non-null `clientId`, a default is trivial to provide. Services that want a real sort key (e.g., `created_at`) override the default — same code path, just custom config.

**Cost:** Every write now computes a paging key, even for services that never call `loadPage`. The cost is a single property read per write, negligible.

### 6. Eight static SQLDelight queries for the no-filter path

**Decision:** The no-filter path has eight hand-written SQLDelight queries: `(first / next page) × (ASC / DESC) × (with / without sync_status filter)`.

**Why not unify into one query with conditional clauses:**
- SQLDelight cannot parameterize `ORDER BY` direction, so ASC and DESC must be separate.
- Combining first-page and next-page into one query requires a nullable cursor parameter and `(:cursor_key IS NULL OR paging_key > :cursor_key)`. SQLDelight 2.x's parameter-type inference treats this as `String` (non-null), making the unified query uncompilable. Splitting first-page from next-page sidesteps the inference problem and gives each statement a tighter query plan (no `OR (cursor IS NULL)` short-circuit at runtime).

The 8-way dispatch lives in `LocalStoreManager.getPage` and is fully type-safe via SQLDelight codegen. Each branch maps its rows inline because the eight generated row classes are nominally distinct types.

### 7. JSON1 + dynamic SQL for filtering — not generated columns or a side table

**Decision:** Filter queries use `json_extract(data_blob, ?)` evaluated by SQLite. The SQL is built dynamically at runtime via `Filter.toSql()` and executed through the raw `SqlDriver`. Hot paths get expression indexes via `indexedJsonPaths`.

**Alternatives considered:**

- **Side table** (`sync_data_filters(service_name, client_id, key, value)`). Pros: zero JSON1 dependency, all-platforms guaranteed. Cons: every filter projection adds a row per data row (write amplification); compound filters require self-joins (`AND` across N filters → N joins); range queries get awkward.
- **Generated / shadow columns** for declared filterable fields. Pros: B-tree indexes, no JSON1 dependency. Cons: caps the number of filterable fields; columns are per-service but the table is shared (gross schema design); changing the filter set later requires a migration.
- **Pure in-memory predicate** (the existing `getFromLocalStore { O -> Boolean }` overload). Pros: trivial. Cons: O(n) deserialize-everything-then-filter; doesn't scale.

**Why JSON1 wins:**
- Zero write-side cost — `data_blob` is already there.
- Any field, any operator, any compound boolean structure — composes naturally in SQL.
- Indexable on demand via expression indexes; consumers commit to which paths to index, not which fields are queryable.
- JSON1 is built into SQLite since 3.38 (default-on) and available as a compile-time extension since 3.9 (2015). Available everywhere modern: Android 14+ ships SQLite 3.44, iOS 16+ ships 3.39, the JDBC driver bundles 3.45+. The current dialect is `sqlite-3-30` — JSON1 is available but not guaranteed by the dialect alone. Bumping to `sqlite-3-38-dialect` is a follow-up worth tracking.

### 8. Dynamic SQL routing — `loadPage(filter = null)` keeps the static path

**Decision:** `loadPage(filter = null)` (the common case) goes through the eight SQLDelight queries. `loadPage(filter = X)` drops to dynamic SQL via the raw driver.

**Why:** Filter combinations are unbounded — N filter shapes × M operators × compound AND/OR groupings. Static SQLDelight codegen can't enumerate them. Dynamic SQL is the necessary escape hatch, but it's contained inside `LocalStoreManager.getPageWithFilter` and the small `FilterSqlBuilder.kt` helper file. Consumers who don't filter pay nothing for the feature; consumers who do filter accept the loss of SQLDelight's compile-time verification only on that path.

### 9. Opt-in expression indexes via `indexedJsonPaths`

**Decision:** Services declare hot filter paths via `indexedJsonPaths: List<String>`. The library creates `CREATE INDEX IF NOT EXISTS ... ON sync_data(service_name, json_extract(data_blob, '$.field'))` on first filter use. Path strings are validated against a strict regex before being interpolated into DDL.

**Why:**
- Filtering on un-indexed paths still works (SQLite falls back to a scan), so consumers can experiment without commitment.
- Forcing the index declaration creates a moment where the consumer thinks about the cost, rather than silently writing performance bugs.
- Indexes are created lazily and idempotently — re-instantiating `LocalStoreManager` doesn't repeat the work.

**Path validation:** Regex `^\$(\.[A-Za-z_][A-Za-z0-9_]*(\[\d+\])?)+$` rejects characters that could break `CREATE INDEX` SQL when interpolated (single quotes, semicolons, etc.). Index names are slugified from the path: `$.created_at` → `idx_<service>_created_at`.

### 10. Separate `:paging` module for `BuoyientPagingSource`

**Decision:** `BuoyientPagingSource` ships in a new Android-only `:paging` module, not in `:syncable-objects`. Modeled after the existing `:hilt` module.

**Why:** `androidx.paging` is Android-only, but `:syncable-objects` is KMP (Android, iOS, JVM). Putting `BuoyientPagingSource` in core would either pull `androidx.paging` into the iOS XCFramework (broken) or require `expect`/`actual` no-ops on non-Android targets (gross). A separate Android-only module is the established pattern in this repo.

The `:paging` module depends only on `:syncable-objects` and `androidx.paging.runtime`. It's published as `syncable-objects-paging`.

### 11. `PageCursor` is opaque, not derivable

**Decision:** `PageResult.nextCursor` is the canonical way to get the next page's cursor. Consumers don't construct `PageCursor` themselves from item fields.

**Why:** The cursor is a `(paging_key, client_id)` tuple, and `paging_key` comes from the configured extractor. Letting consumers construct cursors invites mismatches between their cursor values and what the SQL expects. `BuoyientPagingSource` delegates entirely to `PageResult.nextCursor` — no extractor passed in twice.

### 12. Driver-access refactor: `SyncDatabaseHandle`

**Decision:** Added `SyncDatabaseHandle(database, driver)` and `createSyncDatabaseHandle()` expect/actuals. Existing `createSyncDatabase()` stays as a thin wrapper for back-compat.

**Why:** Filter queries need raw `SqlDriver` access, and SQLDelight 2.x exposes the driver only as `internal` to its own module. The handle bundles database + driver so callers that override one always set the other matching it. `Buoyient.databaseHandle` is the preferred public setter; `Buoyient.database` is preserved but documented as insufficient for filter queries unless `DatabaseOverride.driver` is also set.

`TestServiceEnvironment` now uses `TestDatabaseFactory.createInMemoryHandle()` and sets both override fields.

### 13. Bidirectional pagination via sealed `PageDirection` + mirrored predicate

**Decision:** `loadPage` takes a `direction: PageDirection` parameter — a sealed interface with three cases: `FromHead`, `Forward(afterCursor)`, `Backward(beforeCursor)`. `PageResult<O>` carries both `nextCursor` and `prevCursor`. Items are always returned in the configured sort order regardless of direction.

**Why a sealed interface, not a `(cursor, direction)` pair:** A nullable cursor + enum direction has invalid states — "backward from null" has no meaningful semantics. The sealed shape makes those states unrepresentable: `FromHead` carries no cursor, `Forward` and `Backward` each require one. The compiler enforces the rule that "backward needs a cursor" before any test does. The directional field names (`afterCursor` / `beforeCursor`) also bake exclusivity into the API — there's no inclusive-vs-exclusive toggle to set wrong.

**Mirrored predicate strategy:** A `Backward` load with sort order ASC translates to SQL `cursor < ?` and `ORDER BY ... DESC`, then the result list is reversed in Kotlin to restore ASC order. The full table:

| sortOrder | direction | predicate op | ORDER BY | post-process     |
|-----------|-----------|--------------|----------|------------------|
| ASC       | Forward   | `>`          | ASC      | none             |
| ASC       | Backward  | `<`          | DESC     | reverse the list |
| DESC      | Forward   | `<`          | DESC     | none             |
| DESC      | Backward  | `>`          | ASC      | reverse the list |

**SQL routing:** Backward loads route through the dynamic-SQL builder (shared with the filter path) rather than doubling the static SQLDelight query surface to 16 statements. Backward + filter and backward + no-filter share one builder. The static 8-query fast path is preserved for the hot forward case.

**Cost:** Backward loads require a `SqlDriver` even when no filter is present — matches what filter queries already need. In practice anyone using `BuoyientPagingSource` (which is where prepends originate) is on the driver-available path already.

**`PagingSource` integration:** `BuoyientPagingSource` maps `LoadParams.Prepend` → `PageDirection.Backward`, `LoadParams.Append` → `PageDirection.Forward`, `LoadParams.Refresh` → `PageDirection.Forward` (or `FromHead` when the key is null). `getRefreshKey` simplifies to `anchorPage.prevKey` — the cursor that originally loaded the anchor page.

---

## Schema & migration

**Schema change** (`SyncData.sq`):
```sql
ALTER TABLE sync_data ADD COLUMN paging_key TEXT;
CREATE INDEX idx_sync_data_paging_key ON sync_data(service_name, paging_key);
```

The column is nullable (no `NOT NULL`), so rows that existed before this column was added remain valid. New writes always populate `paging_key` (the default extractor returns the non-null `clientId`).

**Migration file:** `syncable-objects/src/commonMain/sqldelight/migrations/1.sqm` contains the ALTER + CREATE INDEX.

**Migration verification:** `verifyMigrations = true` is enabled in `syncable-objects/build.gradle.kts`, with `schemaOutputDirectory = src/commonMain/sqldelight/databases/`. Two schema snapshots are committed there:

- `1.db` — schema baseline before `1.sqm` is applied (no `paging_key` column, no `idx_sync_data_paging_key`).
- `2.db` — current schema (with the column and index).

On every build, SQLDelight applies `1.sqm` to `1.db` and asserts the result matches the current `.sq` schema (and `2.db`). Drift between the migration and the live schema fails the build at compile time.

Producing snapshots for future migrations: each new `.sqm` file should be paired with a regenerated snapshot. Run `./gradlew :syncable-objects:generateCommonMainSyncDatabaseSchema` after editing `.sq` and `.sqm` files to refresh the highest-numbered snapshot, then commit it. (Lower-numbered snapshots represent past schema versions — once committed they should not be regenerated.)

**Backfill:** Not performed. Old rows have `paging_key = NULL` until rewritten (sync, update, etc.). NULL values sort to the start under ASC and the end under DESC. For a service adopting paging on a large existing dataset, a one-shot `UPDATE sync_data SET paging_key = client_id WHERE paging_key IS NULL` would clean things up — left as a follow-up since the typical adoption path involves cold data already going through sync-down.

---

## Composability matrix

`loadPage` accepts four orthogonal inputs that compose freely:

| Input | Affects |
|---|---|
| `direction` | Adds the `(paging_key, client_id)` cursor predicate to the WHERE clause (flipped for `Backward`); `FromHead` adds no cursor predicate |
| `loadSize` | Sets the SQL `LIMIT` |
| `syncStatus` | Adds `sync_status = ?` to the WHERE clause |
| `filter` | Adds the filter's rendered SQL fragment to the WHERE clause |

In the no-filter path, the (cursor, sync_status) combinations dispatch to the eight static queries. In the filter path, all four inputs are folded into a single dynamically-built WHERE clause.

The `pagingConfig.sortOrder` is a fifth dimension picked by the service at construction (not per-call). It selects ASC vs DESC variants of the cursor predicate and `ORDER BY` clause.

---

## Performance characteristics

| Operation | No filter | With filter (indexed path) | With filter (un-indexed path) |
|---|---|---|---|
| First page | B-tree seek + sequential read of `loadSize` rows | Same | Full table scan, terminating at `loadSize` matches |
| Next page | B-tree seek by `(paging_key, client_id)` + sequential read | Same | Same as un-indexed first page |
| Insert / update | One additional column write (paging_key); index updates if `paging_key` changes | Same; plus per-indexed-path index update | Same; no extra cost for un-indexed paths |

Index storage: `idx_sync_data_paging_key` is mandatory. Each path in `indexedJsonPaths` adds one expression index.

---

## Known limitations / future work

1. **String-typed JSON paths.** A generated typed-paths wrapper (`Item.Path.status`) would prevent typos but is its own project.
3. **`sqlite-3-30-dialect` predates JSON1's default-on threshold.** JSON1 works on all platforms buoyient targets but isn't dialect-guaranteed. Bump to `sqlite-3-38-dialect` to make this explicit.
4. **`paging_key` lexicographic ordering.** Documented in `PagingConfig` KDoc with examples; a typed `PagingKey` sealed type would prevent integer-formatting mistakes but adds API surface. Revisit if real bugs surface.
5. **Existing rows with `paging_key = NULL`.** Backfill in migration is straightforward but requires picking a default value that may not match a custom extractor. Currently left to natural rewrite via sync.
6. **`UPDATE` on a row whose underlying paging-key field changes triggers an index-rewrite.** Not a correctness issue, but worth noting if a service uses a hot-changing field as its paging key.
7. **Dynamic filter swapping in `BuoyientPagingSource` requires reconstructing the source.** This is the canonical Paging 3 pattern (see KDoc), but consumers unfamiliar with `flatMapLatest` over a filter `Flow` may find it less obvious than a setter. A `setFilter` API that calls `invalidate()` could be added if demand surfaces.

---

## Files changed / added

**Added:**
- `syncable-objects/src/commonMain/kotlin/com/elvdev/buoyient/serviceconfigs/PagingConfig.kt`
- `syncable-objects/src/commonMain/kotlin/com/elvdev/buoyient/datatypes/PageResult.kt` (`PageCursor` + `PageResult`)
- `syncable-objects/src/commonMain/kotlin/com/elvdev/buoyient/datatypes/Filter.kt`
- `syncable-objects/src/commonMain/kotlin/com/elvdev/buoyient/managers/FilterSqlBuilder.kt`
- `syncable-objects/src/commonMain/kotlin/com/elvdev/buoyient/globalconfigs/SyncDatabaseHandle.kt`
- `syncable-objects/src/commonMain/sqldelight/migrations/1.sqm`
- `syncable-objects/src/commonMain/sqldelight/databases/{1,2}.db` — schema snapshots backing `verifyMigrations = true`
- `paging/build.gradle.kts` + `paging/src/main/kotlin/com/elvdev/buoyient/paging/BuoyientPagingSource.kt`

**Modified:**
- `syncable-objects/src/commonMain/sqldelight/.../SyncData.sq` — `paging_key` column + index, 8 keyset queries, write statements updated to write the paging key
- `syncable-objects/src/commonMain/kotlin/com/elvdev/buoyient/managers/LocalStoreManager.kt` — paging + filter execution
- `syncable-objects/src/commonMain/kotlin/com/elvdev/buoyient/SyncableObjectService.kt` — `pagingConfig`, `indexedJsonPaths`, `loadPage`
- `syncable-objects/src/{androidMain,jvmMain,iosMain}/.../DatabaseProvider*.kt` — handle support
- `syncable-objects/src/commonMain/kotlin/com/elvdev/buoyient/globalconfigs/{Buoyient,DatabaseOverride}.kt`
- `testing/src/commonMain/.../TestDatabaseFactory.kt` + jvm/ios actuals
- `testing/src/commonMain/.../TestServiceEnvironment.kt` — uses handle, sets both override fields
- `settings.gradle.kts`, `gradle/libs.versions.toml` — `:paging` module + `androidx.paging.runtime` alias
