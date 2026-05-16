package com.elvdev.buoyient.datatypes

/**
 * Selects which page of items [com.elvdev.buoyient.SyncableObjectService.loadPage] returns
 * relative to a [PageCursor].
 *
 * Modeled as a sealed hierarchy so each case carries exactly the parameters it needs:
 * [FromHead] takes no cursor (it starts at the configured sort order's beginning),
 * [Forward] requires an `afterCursor`, and [Backward] requires a `beforeCursor`. This
 * makes invalid combinations (e.g. "backward from nowhere") unrepresentable.
 *
 * Returned pages are always ordered per the service's
 * [com.elvdev.buoyient.serviceconfigs.PagingConfig.sortOrder] regardless of direction —
 * a [Backward] load returns the items that come before [Backward.beforeCursor] in the
 * sort order, in that same order (not reversed).
 *
 * The boundary cursor is **exclusive** in all cases: [Forward.afterCursor] /
 * [Backward.beforeCursor] are not included in the returned page.
 */
public sealed interface PageDirection {
    /**
     * Start from the head of the configured sort order. Used for the first page in a
     * forward scroll.
     */
    public object FromHead : PageDirection

    /**
     * Load the page strictly after [afterCursor] in the configured sort order. The row
     * matching [afterCursor] itself is not included.
     */
    public data class Forward(public val afterCursor: PageCursor) : PageDirection

    /**
     * Load the page strictly before [beforeCursor] in the configured sort order. The
     * row matching [beforeCursor] itself is not included. Items in the returned page
     * are in the configured sort order, not reversed.
     */
    public data class Backward(public val beforeCursor: PageCursor) : PageDirection
}
