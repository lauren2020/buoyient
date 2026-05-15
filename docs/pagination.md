# Pagination and Filtering

buoyient's `:paging` module provides Jetpack Paging 3 integration for paginated lists backed by the local store. Combined with the `Filter` API, you can display large, filtered, live-updating lists without loading everything into memory.

> **Android only.** The `:paging` module depends on `androidx.paging` and only targets Android. The underlying `loadPage()` and `Filter` APIs are available on all platforms (KMP), but `BuoyientPagingSource` is Android-specific.

---

## Concepts

buoyient uses **keyset cursor pagination**: each page hands back an opaque `PageCursor` that encodes the position of the last item. The next page request starts after that cursor. This avoids the correctness problems of OFFSET-based pagination when rows are inserted or deleted between loads.

Pagination is **bidirectional**. `BuoyientPagingSource` translates Paging 3's `LoadParams.Append` into `PageDirection.Forward` and `LoadParams.Prepend` into `PageDirection.Backward`. Scrolling up via prepend is most useful when the pager has a non-null `initialKey` and the user starts mid-list.

---

## Step 1: Add the dependency

```kotlin
dependencies {
    implementation("com.elvdev.buoyient:syncable-objects-paging:<version>")
}
```

The `:paging` module depends on `:syncable-objects` transitively — you do not need to add both.

---

## Step 2: Configure `pagingConfig` in your service

Override `pagingConfig` in your `SyncableObjectService` to control how pages are ordered:

```kotlin
import com.elvdev.buoyient.serviceconfigs.PagingConfig
import com.elvdev.buoyient.serviceconfigs.SortOrder

class ItemService(...) : SyncableObjectService<Item, ItemRequestTag>(
    serializer = Item.serializer(),
    serverProcessingConfig = ItemServerProcessingConfig(),
    serviceName = "items",
) {
    override val pagingConfig = PagingConfig(
        keyExtractor = { item -> item.createdAt },  // field to page by (default: clientId)
        sortOrder = SortOrder.DESC,                  // newest first (default: DESC)
    )
}
```

If you leave `pagingConfig` at its default, pages are ordered by `clientId` descending — fine for most cases.

---

## Step 3: Declare indexes for filtered fields (optional but recommended)

If you filter by a specific field frequently, declare it in `indexedJsonPaths` so buoyient creates a SQLite expression index at startup. Without an index, filtered queries scan the full table.

```kotlin
class ItemService(...) : SyncableObjectService<Item, ItemRequestTag>(...) {
    override val indexedJsonPaths = listOf("$.status", "$.category")
}
```

The path syntax is a JSON path expression matching the field's location in the serialized `data_blob`. Use `$.fieldName` for top-level fields.

---

## Step 4: Use `BuoyientPagingSource`

`BuoyientPagingSource` implements `PagingSource<PageCursor, O>` for Paging 3. Pass it as the factory to `Pager`:

### No filter

```kotlin
val pager = Pager(PagingConfig(pageSize = 20)) {
    BuoyientPagingSource(itemService)
}
val items: Flow<PagingData<Item>> = pager.flow
```

### Static filter

```kotlin
val pager = Pager(PagingConfig(pageSize = 20)) {
    BuoyientPagingSource(
        service = itemService,
        filter = Filter.eq("$.status", "active"),
    )
}
```

### Filter by sync status

```kotlin
val pager = Pager(PagingConfig(pageSize = 20)) {
    BuoyientPagingSource(
        service = itemService,
        syncStatus = "synced",  // only show fully-synced items
    )
}
```

### Auto-refresh on background sync-down

By default, `BuoyientPagingSource` is a one-shot snapshot. Set `autoRefreshOnLocalStoreChange = true` to automatically invalidate and reload whenever the service's local store is written (sync-down inserts, sync-up merges, or local create/update/void):

```kotlin
val pager = Pager(PagingConfig(pageSize = 20)) {
    BuoyientPagingSource(itemService, autoRefreshOnLocalStoreChange = true)
}
```

### Dynamic filter (driven by search/UI state)

When the filter changes at runtime (e.g., a search field), use `flatMapLatest` to emit a fresh `Pager` — and therefore a fresh `BuoyientPagingSource` — for each new filter value:

```kotlin
val pagedItems: Flow<PagingData<Item>> = filterFlow.flatMapLatest { filter ->
    Pager(PagingConfig(pageSize = 20)) {
        BuoyientPagingSource(itemService, filter = filter)
    }.flow
}
```

`flatMapLatest` cancels the previous pager's flow automatically, so the collector always sees items from the current filter only.

---

## Filter reference

`Filter` is a sealed interface in `com.elvdev.buoyient.datatypes`. Construct filters using the companion factories:

### Comparison

```kotlin
Filter.eq("$.status", "active")      // field == value
Filter.ne("$.status", "voided")      // field != value
Filter.gt("$.score", "100")          // field > value (lexicographic)
Filter.gte("$.score", "100")         // field >= value
Filter.lt("$.priority", "5")         // field < value
Filter.lte("$.priority", "5")        // field <= value
```

### Membership / pattern

```kotlin
Filter.`in`("$.category", listOf("food", "drink"))   // field IN (...)
Filter.like("$.name", "%milk%")                      // SQL LIKE pattern
```

### Null checks

```kotlin
Filter.isNull("$.archivedAt")
Filter.isNotNull("$.serverId")
```

### Boolean combinators

```kotlin
Filter.and(
    Filter.eq("$.status", "active"),
    Filter.isNotNull("$.serverId"),
)
Filter.or(
    Filter.eq("$.category", "food"),
    Filter.eq("$.category", "drink"),
)
Filter.not(Filter.eq("$.status", "voided"))
```

---

## Scroll-position preservation during sync

`BuoyientPagingSource.getRefreshKey()` returns the cursor that originally loaded the page containing the user's anchor position. When a background sync-down invalidates the source (via `autoRefreshOnLocalStoreChange`), Paging 3 reloads starting at the same window the user was viewing rather than jumping back to the top of the list.

---

## Using `loadPage()` directly

If you need pagination without Paging 3, call `loadPage()` on the service directly. `loadPage()` takes a `PageDirection` that selects which page to fetch:

- `PageDirection.FromHead` — first page from the start of the configured sort order (the default).
- `PageDirection.Forward(cursor)` — page strictly after `cursor`. Used to scroll down.
- `PageDirection.Backward(cursor)` — page strictly before `cursor`, returned in the configured sort order (not reversed). Used to scroll up from a `Forward` cursor.

```kotlin
val firstPage: PageResult<Item> = itemService.loadPage(
    // direction defaults to PageDirection.FromHead
    loadSize = 20,
    filter = Filter.eq("$.status", "active"),
)
val nextPage: PageResult<Item> = itemService.loadPage(
    direction = PageDirection.Forward(firstPage.nextCursor!!),
    loadSize = 20,
)
// To scroll up from a known cursor — typically used when the user starts mid-list:
val previousPage: PageResult<Item> = itemService.loadPage(
    direction = PageDirection.Backward(nextPage.prevCursor!!),
    loadSize = 20,
)
```

`PageResult<O>` contains:
- `items: List<O>` — the items in this page, always in the configured sort order regardless of direction.
- `nextCursor: PageCursor?` — boundary cursor for the page after this one (wrap in `PageDirection.Forward(...)`). `null` means we hit the tail.
- `prevCursor: PageCursor?` — boundary cursor for the page before this one (wrap in `PageDirection.Backward(...)`). `null` means we hit the head.

`Backward` requires a non-null cursor — by design, "backward from nothing" is unrepresentable. To load the first page, use `FromHead`.
