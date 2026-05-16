# Pagination on iOS

This guide covers paged list rendering on iOS using buoyient's `BuoyientPagedList`. If you're on Android, see [pagination.md](pagination.md) for the Jetpack Paging 3 integration — most iOS-specific notes don't apply there.

**Prerequisites:** an iOS app with buoyient already set up — see [setup-ios.md](setup-ios.md). The `BuoyientPagedList` class is in the existing `Buoyient.xcframework`, no extra package needed.

---

## What it gives you

`BuoyientPagedList` is a stateful coordinator over `SyncableObjectService.loadPage()`. It manages:

- The current loaded items as a `StateFlow<[Item]>` (exposed to Swift as an `AsyncSequence`).
- Forward/backward cursors so `loadMore()` and `loadPrevious()` know where to continue from.
- A `LoadState` (sealed) — `Idle`, `LoadingInitial`, `LoadingMore`, `Error(throwable)`.
- Optional auto-refresh: subscribe to `service.localStoreChanges` and re-fetch on every local write.

What it does **not** do — by design:
- No view recycling, diff calculation, or placeholder rows. SwiftUI's `List` and `LazyVStack` handle that already.
- No Paging-3-style "viewport-driven" prefetch. You decide when to call `loadMore()` (typically from `.task` on the last visible row).

---

## Quick start — SwiftUI

```swift
import SwiftUI
import Buoyient

struct TodoListView: View {
    let service: TodoService

    @State private var items: [TodoItem] = []
    @State private var pagedList: BuoyientPagedList<TodoItem, TodoRequestTag>?

    var body: some View {
        List(items, id: \.clientId) { item in
            TodoRow(item: item)
                .task {
                    // Trigger loadMore when the last row appears.
                    if item.clientId == items.last?.clientId {
                        try? await pagedList?.loadMore()
                    }
                }
        }
        .refreshable {
            try? await pagedList?.refresh()
        }
        .task {
            let list = BuoyientPagedList(
                service: service,
                pageSize: 20,
                syncStatus: nil,
                filter: nil,
                autoRefreshOnLocalStoreChange: true,
                initialKey: nil
            )
            pagedList = list
            try? await list.refresh()

            // Bridge the Kotlin StateFlow into SwiftUI state.
            for await snapshot in list.items {
                items = snapshot
            }
        }
        .onDisappear {
            pagedList?.close()
        }
    }
}
```

### What's happening

1. **`.task` runs when the view appears.** Construct the `BuoyientPagedList` with the service and config, then call `refresh()` to load the first page.
2. **`for await snapshot in list.items`** subscribes to the Kotlin `StateFlow<List<Item>>`. SKIE turns it into a Swift `AsyncSequence` — each `snapshot` is the current item list. The loop never returns until the `.task` is cancelled (e.g. on view disappear).
3. **`.task` on each row** detects when the bottom row appears and asks the list to load more.
4. **`.refreshable`** maps to the `refresh()` method, which resets the list to the first page.
5. **`.onDisappear`** closes the list to detach the `localStoreChanges` subscription.

---

## Constructor parameters

```swift
BuoyientPagedList(
    service: SyncableObjectService<O, T>,    // your service
    pageSize: Int = 20,                       // items per page request
    syncStatus: String? = nil,                // optional: only rows with this sync status
    filter: Filter? = nil,                    // optional: predicate over data_blob
    sortOrder: PagingConfigSortOrder? = nil,  // optional: override service sort direction
    autoRefreshOnLocalStoreChange: Bool = false,
    initialKey: PageCursor? = nil             // optional: start mid-list at this cursor
)
```

### `initialKey` — starting mid-list

Pass a `PageCursor` to start the first load *forward from* that cursor. Useful for "jump to this item" UX or restoring scroll position:

```swift
let cursor = PageCursor(key: lastSeenTimestamp, clientId: lastSeenClientId)
let list = BuoyientPagedList(service: service, pageSize: 20, initialKey: cursor)
```

With a non-null `initialKey`, `hasMoreBackward` starts as `true` and you can call `loadPrevious()` to walk back toward the head.

### `sortOrder` — flip direction per list

Pass `sortOrder` to override the service's configured direction for this list only. Useful for "newest first" vs "oldest first" toggles:

```swift
@State private var sortOrder: PagingConfigSortOrder = .desc

.task(id: sortOrder) {                       // re-runs when the toggle changes
    let list = BuoyientPagedList(
        service: service,
        pageSize: 20,
        sortOrder: sortOrder
    )
    // ... bind to `items` as above
}
```

Cursors aren't meaningful across directions, so reconstructing on flip is the right move — `.task(id:)` cancels the old list and starts a fresh one from the head.

### `filter` and `syncStatus` — bound at construction

These are fixed for the lifetime of a `BuoyientPagedList`. To change them (e.g., user toggles "show only unsynced"), construct a new list. The SwiftUI idiom is to make the construction depend on the filter `@State`:

```swift
@State private var filterText: String = ""

.task(id: filterText) {                       // re-runs whenever filterText changes
    let list = BuoyientPagedList(
        service: service,
        pageSize: 20,
        filter: filterText.isEmpty ? nil : Filter.like(path: "$.title", pattern: "%\(filterText)%")
    )
    // ... bind to `items` as above
}
```

`.task(id:)` cancels the old subscription and starts a fresh one when `filterText` changes.

---

## Reading `loadState`

`loadState` is exposed as a `StateFlow<BuoyientPagedListLoadState>`. SKIE generates a sealed Swift enum so you can pattern-match:

```swift
.task {
    for await state in list.loadState {
        switch onEnum(of: state) {
        case .idle:
            // nothing in flight
        case .loadingInitial:
            // showing skeleton / spinner during first refresh
        case .loadingMore:
            // showing "loading more" indicator at the bottom
        case .error(let err):
            // err.throwable contains the underlying exception
            print("Page load failed: \(err.throwable)")
        }
    }
}
```

You can drive a Boolean `isLoading` SwiftUI binding from this, or show inline error UI.

---

## Auto-refresh on local store changes

`autoRefreshOnLocalStoreChange: true` makes the list re-fetch from page 1 every time the service's local store is written — sync-down inserts, sync-up merges, local create/update/void. The list stays in sync with the database without manual intervention.

Trade-off: an auto-refresh **resets to the first page** and loses scroll position. For lists where this matters, leave the flag off and refresh manually via `.refreshable`.

(The Android `BuoyientPagingSource` handles this differently — Paging 3's `invalidate()` reloads pages around the user's current anchor. The same approach would require more bookkeeping in `BuoyientPagedList`; we can add it if there's demand.)

---

## Threading and SwiftUI

`BuoyientPagedList` runs its internal coroutines on a Kotlin `Dispatchers.Default` scope. Its `StateFlow` updates publish from whatever thread completed the load (typically a background pool).

SwiftUI views read from `@State` properties, which require updates on the main actor. When you do:

```swift
for await snapshot in list.items {
    items = snapshot                          // crosses into main actor
}
```

Inside a SwiftUI `.task`, the closure is already `@MainActor`-isolated, so writes to `items` happen on main automatically. **You don't need to dispatch manually.**

If you're driving an `ObservableObject` from outside a SwiftUI view, wrap the binding update in `MainActor.run { ... }` or annotate the bridge type `@MainActor`.

---

## An optional Swift wrapper

The pattern above (`@State items` + `for await` loop in `.task`) is workable but boilerplate-heavy. A small Swift wrapper makes the call site idiomatic:

```swift
import Buoyient
import Observation

@Observable
@MainActor
final class PagedList<O: AnyObject> {
    private(set) var items: [O] = []
    private(set) var loadState: BuoyientPagedListLoadState = BuoyientPagedListLoadStateIdle()

    private let kotlin: BuoyientPagedList<O, AnyObject>
    private var subscription: Task<Void, Never>?

    init(_ kotlin: BuoyientPagedList<O, AnyObject>) {
        self.kotlin = kotlin
        subscription = Task { @MainActor in
            await withTaskGroup(of: Void.self) { group in
                group.addTask {
                    for await snapshot in kotlin.items {
                        self.items = snapshot as? [O] ?? []
                    }
                }
                group.addTask {
                    for await state in kotlin.loadState {
                        self.loadState = state
                    }
                }
            }
        }
    }

    func refresh() async { try? await kotlin.refresh() }
    func loadMore() async { try? await kotlin.loadMore() }
    func loadPrevious() async { try? await kotlin.loadPrevious() }

    deinit {
        subscription?.cancel()
        kotlin.close()
    }
}
```

Usage drops to:

```swift
@State private var list = PagedList(
    BuoyientPagedList(service: service, pageSize: 20)
)

List(list.items, id: \.clientId) { item in
    TodoRow(item: item).task {
        if item.clientId == list.items.last?.clientId { await list.loadMore() }
    }
}
.refreshable { await list.refresh() }
```

The wrapper lives in your app code, not in buoyient. We may ship something like it in a future SPM extension if the pattern proves widely useful.

---

## When to use `BuoyientPagedList` vs `loadPage()` directly

| Use case | API |
|---|---|
| Paged SwiftUI list with scroll-to-load-more | `BuoyientPagedList` |
| One-shot query (e.g. "give me 50 items matching X") | `service.loadPage()` directly |
| Custom UI (collection view, infinite scroll horizontally, search results) | `service.loadPage()` directly, manage state yourself |

`BuoyientPagedList` is opinionated about "current items in sort order, prepend at top, append at bottom." When that fits your UI, it saves you state-machine boilerplate. When it doesn't, drop to `loadPage()` and build what you need.

---

## Related docs

- [pagination.md](pagination.md) — Android-specific Jetpack Paging 3 integration.
- [setup-ios.md](setup-ios.md) — iOS setup, framework distribution, background sync.
- [creating-a-service.md](creating-a-service.md) — defining the `SyncableObjectService` that `BuoyientPagedList` paginates.
