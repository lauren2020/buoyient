package com.elvdev.buoyient.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.elvdev.buoyient.ServiceRequestTag
import com.elvdev.buoyient.SyncableObject
import com.elvdev.buoyient.SyncableObjectService

/**
 * A [PagingSource] backed by a [SyncableObjectService]'s local store, using keyset cursor
 * pagination ordered by the service's `pagingKeyExtractor` value.
 *
 * The [Key] type is [String] (the `paging_key` cursor value); `null` means "start from the
 * beginning". Pagination is **forward-only**: [prevKey] is always `null`.
 *
 * **Setup:** Configure [SyncableObjectService.pagingKeyExtractor] in the service constructor
 * and pass the same extractor here so the paging source can derive the next cursor from the
 * last loaded item.
 *
 * **Usage:**
 * ```kotlin
 * val pager = Pager(PagingConfig(pageSize = 20)) {
 *     BuoyientPagingSource(
 *         service = myService,
 *         pagingKeyExtractor = MyModel.PAGING_KEY,
 *     )
 * }
 * ```
 *
 * @param service the [SyncableObjectService] to load pages from.
 * @param pagingKeyExtractor extracts the ordering key from a domain object — must be the same
 *   function passed to [SyncableObjectService.pagingKeyExtractor].
 * @param syncStatus if non-null, only rows with this sync status are included.
 */
public class BuoyientPagingSource<O : SyncableObject<O>, T : ServiceRequestTag>(
    private val service: SyncableObjectService<O, T>,
    private val pagingKeyExtractor: (O) -> String,
    private val syncStatus: String? = null,
) : PagingSource<String, O>() {

    override suspend fun load(params: LoadParams<String>): LoadResult<String, O> {
        return try {
            val cursor = params.key
            val items = service.loadPage(
                afterCursor = cursor,
                loadSize = params.loadSize,
                syncStatus = syncStatus,
            )
            LoadResult.Page(
                data = items,
                prevKey = null,
                nextKey = if (items.size < params.loadSize) null
                          else pagingKeyExtractor(items.last()),
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<String, O>): String? = null
}
