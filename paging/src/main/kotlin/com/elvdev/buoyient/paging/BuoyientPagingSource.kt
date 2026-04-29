package com.elvdev.buoyient.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.elvdev.buoyient.ServiceRequestTag
import com.elvdev.buoyient.SyncableObject
import com.elvdev.buoyient.SyncableObjectService
import com.elvdev.buoyient.datatypes.Filter
import com.elvdev.buoyient.datatypes.PageCursor

/**
 * A [PagingSource] backed by a [SyncableObjectService]'s local store, using keyset cursor
 * pagination ordered per the service's
 * [com.elvdev.buoyient.serviceconfigs.PagingConfig].
 *
 * The [Key] type is [PageCursor]; `null` means "start from the beginning". Pagination is
 * **forward-only**: [LoadResult.Page.prevKey] is always `null`.
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
 */
public class BuoyientPagingSource<O : SyncableObject<O>, T : ServiceRequestTag>(
    private val service: SyncableObjectService<O, T>,
    private val syncStatus: String? = null,
    private val filter: Filter? = null,
) : PagingSource<PageCursor, O>() {

    override suspend fun load(params: LoadParams<PageCursor>): LoadResult<PageCursor, O> {
        return try {
            val result = service.loadPage(
                afterCursor = params.key,
                loadSize = params.loadSize,
                syncStatus = syncStatus,
                filter = filter,
            )
            LoadResult.Page(
                data = result.items,
                prevKey = null,
                nextKey = result.nextCursor,
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<PageCursor, O>): PageCursor? = null
}
