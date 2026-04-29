package com.elvdev.buoyient.datatypes

/**
 * Position of a row in a keyset-paginated result, used as the cursor between pages.
 *
 * Combines the configured paging key with the row's `client_id` so two rows that
 * share a paging key (e.g. identical timestamps) still produce a stable, unambiguous
 * resume point.
 *
 * Treat this as opaque — pass the [PageResult.nextCursor] from one page back into
 * [com.elvdev.buoyient.SyncableObjectService.loadPage] to fetch the next page.
 */
public data class PageCursor(
    public val key: String,
    public val clientId: String,
)

/**
 * One page of items returned by [com.elvdev.buoyient.SyncableObjectService.loadPage].
 *
 * @property items the rows on this page, ordered per the service's
 *   [com.elvdev.buoyient.serviceconfigs.PagingConfig.sortOrder].
 * @property nextCursor cursor to fetch the next page, or `null` when there are no
 *   more pages (i.e. the page returned fewer items than the requested `loadSize`).
 */
public data class PageResult<O>(
    public val items: List<O>,
    public val nextCursor: PageCursor?,
)
