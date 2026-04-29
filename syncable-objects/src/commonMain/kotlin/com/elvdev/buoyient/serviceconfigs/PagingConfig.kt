package com.elvdev.buoyient.serviceconfigs

/**
 * Per-service configuration that enables keyset cursor pagination on the local store.
 *
 * When supplied to [com.elvdev.buoyient.SyncableObjectService], buoyient stores the
 * extracted [keyExtractor] value in a dedicated indexed column on every write, then
 * orders pages by that value plus `client_id` as a stable tiebreaker.
 *
 * **Ordering note:** values are compared lexicographically (SQLite TEXT). Use a format
 * that sorts correctly as text:
 * - ISO 8601 timestamps (e.g. `2024-01-15T10:00:00Z`) ✓
 * - Names, titles, alphabetical strings ✓
 * - Zero-padded integers (e.g. `"%010d".format(n)`) ✓
 * - Raw integer or float `toString()` values ✗ (`"10" < "2"` lexicographically)
 *
 * @property keyExtractor produces the sort key for an object. Must be deterministic
 *   and ideally derived from a stable, server-assigned field (e.g. `created_at`).
 * @property sortOrder direction in which pages are returned. Defaults to
 *   [SortOrder.DESC] — newest-first when paging by timestamp.
 */
public data class PagingConfig<O>(
    public val keyExtractor: (O) -> String,
    public val sortOrder: SortOrder = SortOrder.DESC,
) {
    public enum class SortOrder { ASC, DESC }
}
