package com.elvdev.buoyient.paging

import com.elvdev.buoyient.ServiceRequestTag
import com.elvdev.buoyient.SyncableObject
import com.elvdev.buoyient.SyncableObjectService
import com.elvdev.buoyient.datatypes.Filter
import com.elvdev.buoyient.datatypes.PageCursor
import com.elvdev.buoyient.datatypes.PageDirection
import com.elvdev.buoyient.datatypes.PageResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Stateful coordinator over [SyncableObjectService.loadPage], aimed at iOS / SwiftUI
 * consumers (and any other UI framework that prefers reactive state over the
 * Android-specific `androidx.paging` machinery).
 *
 * Holds the current page state — items plus the forward/backward cursors — and
 * exposes it as [StateFlow]s. SKIE turns those into Swift `AsyncSequence`s so a
 * SwiftUI view can drive a `List` with `.task` and `.refreshable`.
 *
 * On Android, prefer `BuoyientPagingSource` (in the `:paging` module) when
 * integrating with Jetpack Paging 3 — it handles diffing, placeholder rows, and
 * viewport-driven prefetch out of the box. Reach for [BuoyientPagedList] only on
 * non-Paging-3 paths (or when sharing UI code across platforms via Compose
 * Multiplatform).
 *
 * **Lifecycle.** [close] cancels the internal scope and detaches the
 * [SyncableObjectService.localStoreChanges] subscription. iOS consumers typically
 * close the list in a SwiftUI `.onDisappear` or `deinit`. The list is single-use —
 * after [close] the methods no-op.
 *
 * **Concurrency.** Calls to [refresh], [loadMore], and [loadPrevious] are serialized
 * via an internal [Mutex] — a second call that arrives while one is in flight waits
 * for the first to complete. State flows update atomically.
 *
 * **Pagination semantics** mirror `BuoyientPagingSource`:
 *  - [initialKey] = `null` → first load starts at [PageDirection.FromHead]
 *  - [initialKey] = non-null → first load is `Forward(initialKey)`, leaving room
 *    for [loadPrevious] to walk back toward the head
 *  - Items are always in the configured sort order, regardless of direction.
 *
 * **Filter / sync status** are bound at construction (matching Paging 3's pattern).
 * To change them, build a new [BuoyientPagedList] — typically by re-running the
 * factory in a SwiftUI `.task(id: filter)` or equivalent.
 *
 * @param service the [SyncableObjectService] to load pages from.
 * @param pageSize number of items per page request.
 * @param syncStatus if non-null, only rows with this sync status are included.
 * @param filter optional predicate over `data_blob`; see [Filter].
 * @param autoRefreshOnLocalStoreChange when `true`, subscribes to
 *   [SyncableObjectService.localStoreChanges] and calls [refresh] on each emission.
 *   Useful for keeping a SwiftUI list in sync with background sync-downs.
 * @param initialKey if non-null, the first load is `Forward(initialKey)` instead of
 *   `FromHead`. Use this to start the list mid-data (e.g., resume scroll position).
 */
public class BuoyientPagedList<O : SyncableObject<O>, T : ServiceRequestTag>(
    private val service: SyncableObjectService<O, T>,
    private val pageSize: Int = 20,
    private val syncStatus: String? = null,
    private val filter: Filter? = null,
    autoRefreshOnLocalStoreChange: Boolean = false,
    private val initialKey: PageCursor? = null,
) {

    public sealed interface LoadState {
        public data object Idle : LoadState
        public data object LoadingInitial : LoadState
        public data object LoadingMore : LoadState
        public data class Error(public val throwable: Throwable) : LoadState
    }

    private val _items = MutableStateFlow<List<O>>(emptyList())
    public val items: StateFlow<List<O>> = _items.asStateFlow()

    private val _loadState = MutableStateFlow<LoadState>(LoadState.Idle)
    public val loadState: StateFlow<LoadState> = _loadState.asStateFlow()

    private val _hasMoreForward = MutableStateFlow(true)
    public val hasMoreForward: StateFlow<Boolean> = _hasMoreForward.asStateFlow()

    /**
     * Whether the list has more pages backward. Only meaningful when [initialKey]
     * was non-null — a list that started from the head has nothing before it.
     */
    private val _hasMoreBackward = MutableStateFlow(initialKey != null)
    public val hasMoreBackward: StateFlow<Boolean> = _hasMoreBackward.asStateFlow()

    // Cursors representing the bounds of currently-loaded items. Updated atomically
    // with [_items] under [mutex]. Conceptually: `nextCursor` is the cursor we'd
    // pass as `Forward(nextCursor)` to fetch the next forward page; `prevCursor` is
    // the cursor for `Backward(prevCursor)` to fetch the previous backward page.
    private var nextCursor: PageCursor? = null
    private var prevCursor: PageCursor? = null

    private val mutex = Mutex()
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var refreshSubscription: Job? = null
    private var closed = false

    init {
        if (autoRefreshOnLocalStoreChange) {
            refreshSubscription = service.localStoreChanges
                .onEach { refresh() }
                .launchIn(scope)
        }
    }

    /**
     * Reset to the first page (either [PageDirection.FromHead] or
     * `Forward(initialKey)` if one was supplied), replacing all current items.
     * Concurrent callers wait for the in-flight load to complete.
     */
    public suspend fun refresh() {
        if (closed) return
        mutex.withLock {
            _loadState.value = LoadState.LoadingInitial
            try {
                val direction = initialKey?.let(PageDirection::Forward) ?: PageDirection.FromHead
                val page = loadPage(direction)
                _items.value = page.items
                nextCursor = page.nextCursor
                prevCursor = page.prevCursor
                _hasMoreForward.value = page.nextCursor != null
                _hasMoreBackward.value = page.prevCursor != null
                _loadState.value = LoadState.Idle
            } catch (throwable: Throwable) {
                _loadState.value = LoadState.Error(throwable)
            }
        }
    }

    /**
     * Append the next forward page. No-op if [hasMoreForward] is false or another
     * load is in flight (the second caller waits).
     */
    public suspend fun loadMore() {
        if (closed) return
        mutex.withLock {
            val cursor = nextCursor ?: return@withLock  // already at the tail
            _loadState.value = LoadState.LoadingMore
            try {
                val page = loadPage(PageDirection.Forward(cursor))
                _items.value = _items.value + page.items
                nextCursor = page.nextCursor
                _hasMoreForward.value = page.nextCursor != null
                // prevCursor stays anchored at the original head of the loaded window.
                _loadState.value = LoadState.Idle
            } catch (throwable: Throwable) {
                _loadState.value = LoadState.Error(throwable)
            }
        }
    }

    /**
     * Prepend the previous backward page. No-op if [hasMoreBackward] is false or
     * another load is in flight.
     */
    public suspend fun loadPrevious() {
        if (closed) return
        mutex.withLock {
            val cursor = prevCursor ?: return@withLock  // already at the head
            _loadState.value = LoadState.LoadingMore
            try {
                val page = loadPage(PageDirection.Backward(cursor))
                _items.value = page.items + _items.value
                prevCursor = page.prevCursor
                _hasMoreBackward.value = page.prevCursor != null
                // nextCursor stays anchored at the original tail of the loaded window.
                _loadState.value = LoadState.Idle
            } catch (throwable: Throwable) {
                _loadState.value = LoadState.Error(throwable)
            }
        }
    }

    private fun loadPage(direction: PageDirection): PageResult<O> =
        service.loadPage(
            direction = direction,
            loadSize = pageSize,
            syncStatus = syncStatus,
            filter = filter,
        )

    /** Detach the [localStoreChanges] subscription and cancel any in-flight loads. */
    public fun close() {
        if (closed) return
        closed = true
        refreshSubscription?.cancel()
        scope.cancel()
    }
}
