package com.elvdev.buoyient.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.elvdev.buoyient.ServiceRequestTag
import com.elvdev.buoyient.SyncableObject
import com.elvdev.buoyient.SyncableObjectService
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
 * **Usage:**
 * ```kotlin
 * val pager = Pager(PagingConfig(pageSize = 20)) {
 *     BuoyientPagingSource(myService)
 * }
 * ```
 *
 * @param service the [SyncableObjectService] to load pages from.
 * @param syncStatus if non-null, only rows with this sync status are included.
 */
public class BuoyientPagingSource<O : SyncableObject<O>, T : ServiceRequestTag>(
    private val service: SyncableObjectService<O, T>,
    private val syncStatus: String? = null,
) : PagingSource<PageCursor, O>() {

    override suspend fun load(params: LoadParams<PageCursor>): LoadResult<PageCursor, O> {
        return try {
            val result = service.loadPage(
                afterCursor = params.key,
                loadSize = params.loadSize,
                syncStatus = syncStatus,
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
