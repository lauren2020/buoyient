package com.elvdev.buoyient.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.elvdev.buoyient.ServiceRequestTag
import com.elvdev.buoyient.SyncableObject
import com.elvdev.buoyient.SyncableObjectService
import com.elvdev.buoyient.datatypes.Filter
import com.elvdev.buoyient.datatypes.PageCursor
import com.elvdev.buoyient.datatypes.PageDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn

/**
 * A [PagingSource] backed by a [SyncableObjectService]'s local store, using keyset cursor
 * pagination ordered per the service's
 * [com.elvdev.buoyient.serviceconfigs.PagingConfig].
 *
 * The [Key] type is [PageCursor]; `null` means "start from the head." Pagination is
 * bidirectional — Paging 3 can both append (scroll down) and prepend (scroll up from
 * an `initialKey` mid-list) by translating its [LoadParams] into a [PageDirection].
 *
 * **Setup:** Configure
 * [com.elvdev.buoyient.SyncableObjectService.pagingConfig] in the service constructor;
 * this paging source reads pages and cursor info from
 * [com.elvdev.buoyient.SyncableObjectService.loadPage] directly, so no extractor needs
 * to be passed twice.
 *
 * **Usage — no filter:**
 * ```kotlin
 * val pager = Pager(PagingConfig(pageSize = 20)) {
 *     BuoyientPagingSource(myService)
 * }
 * ```
 *
 * **Usage — static filter:**
 * ```kotlin
 * val pager = Pager(PagingConfig(pageSize = 20)) {
 *     BuoyientPagingSource(myService, filter = Filter.eq("$.status", "active"))
 * }
 * ```
 *
 * **Usage — auto-refresh on background sync-down:**
 * ```kotlin
 * // Set autoRefreshOnLocalStoreChange = true to invalidate this PagingSource
 * // (and trigger Paging 3 to reload pages) every time the service's local store
 * // is written — sync-down inserts, sync-up merges, local create/update/void.
 * val pager = Pager(PagingConfig(pageSize = 20)) {
 *     BuoyientPagingSource(myService, autoRefreshOnLocalStoreChange = true)
 * }
 * ```
 *
 * **Usage — dynamic filter** (filter changes over time, e.g. driven by a search field):
 * ```kotlin
 * // Re-emits a fresh Pager (and therefore a fresh PagingSource) whenever the
 * // filter changes. flatMapLatest cancels the previous pager's flow so callers
 * // see only items from the current filter.
 * val pagedItems: Flow<PagingData<Item>> = filterFlow.flatMapLatest { filter ->
 *     Pager(PagingConfig(pageSize = 20)) {
 *         BuoyientPagingSource(myService, filter = filter)
 *     }.flow
 * }
 * ```
 *
 * The paging source is bound to the [filter] passed at construction time. Changing the
 * filter requires reinstantiating the source (Paging 3's standard pattern for parameterized
 * sources) — the snippet above does this idiomatically.
 *
 * **Indexing.** Filters scan the table unless an expression index exists. Declare hot
 * paths via [SyncableObjectService.indexedJsonPaths] so the library creates the
 * matching index at startup.
 *
 * @param service the [SyncableObjectService] to load pages from.
 * @param syncStatus if non-null, only rows with this sync status are included.
 * @param filter optional predicate over `data_blob` (see [Filter]). Bound at construction;
 *   reinstantiate the source to change it.
 * @param autoRefreshOnLocalStoreChange when `true`, this source subscribes to
 *   [SyncableObjectService.localStoreChanges] and calls [invalidate] on each emission so
 *   Paging 3 reloads pages whenever the service's local store is written. Defaults to
 *   `false` to preserve the existing one-shot behavior.
 */
public class BuoyientPagingSource<O : SyncableObject<O>, T : ServiceRequestTag>(
    private val service: SyncableObjectService<O, T>,
    private val syncStatus: String? = null,
    private val filter: Filter? = null,
    autoRefreshOnLocalStoreChange: Boolean = false,
) : PagingSource<PageCursor, O>() {

    private val refreshScope: CoroutineScope? = if (autoRefreshOnLocalStoreChange) {
        CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    } else {
        null
    }

    init {
        if (refreshScope != null) {
            service.localStoreChanges
                .onEach { invalidate() }
                .launchIn(refreshScope)
            registerInvalidatedCallback {
                refreshScope.cancel()
            }
        }
    }

    override suspend fun load(params: LoadParams<PageCursor>): LoadResult<PageCursor, O> {
        return try {
            val direction = when (params) {
                is LoadParams.Prepend -> PageDirection.Backward(params.key)
                is LoadParams.Append -> PageDirection.Forward(params.key)
                is LoadParams.Refresh -> params.key?.let(PageDirection::Forward)
                    ?: PageDirection.FromHead
            }
            val result = service.loadPage(
                direction = direction,
                loadSize = params.loadSize,
                syncStatus = syncStatus,
                filter = filter,
            )
            LoadResult.Page(
                data = result.items,
                prevKey = result.prevCursor,
                nextKey = result.nextCursor,
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    /**
     * Returns the cursor that originally loaded the page containing the user's anchor
     * position. Re-using that cursor as a forward boundary on refresh reloads the same
     * window the user was looking at, so an invalidate caused by a background sync-down
     * doesn't bounce the list back to the head.
     *
     * The cursor that loaded the anchor page is exactly that page's [LoadResult.Page.prevKey] —
     * the boundary the previous load passed in. For the very first page (or when no anchor
     * is available) we return `null` so refresh starts from [PageDirection.FromHead].
     */
    override fun getRefreshKey(state: PagingState<PageCursor, O>): PageCursor? {
        val anchor = state.anchorPosition ?: return null
        val anchorPage = state.closestPageToPosition(anchor) ?: return null
        return anchorPage.prevKey
    }
}
