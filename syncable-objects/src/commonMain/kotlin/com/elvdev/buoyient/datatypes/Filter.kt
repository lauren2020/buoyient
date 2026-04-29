package com.elvdev.buoyient.datatypes

/**
 * A predicate over a row's `data_blob`, evaluated by SQLite via `json_extract`.
 *
 * Pass to [com.elvdev.buoyient.SyncableObjectService.loadPage] to restrict the page
 * to matching rows. Compose leaf predicates with [and] / [or] / [not] for
 * arbitrary boolean structure.
 *
 * **JSON paths.** Use SQLite's JSON path syntax: `$.field`, `$.nested.field`,
 * `$.array[0]`. The path string is bound as a parameter at execution time.
 *
 * **Performance.** Each unique path used in a filter triggers a full table scan
 * unless a matching expression index exists. Declare hot paths via
 * [com.elvdev.buoyient.SyncableObjectService.indexedJsonPaths] so the library
 * creates `CREATE INDEX ... ON sync_data(service_name, json_extract(...))` at
 * startup; un-indexed paths still work, just slowly.
 *
 * **Type semantics.** `json_extract` returns the JSON-typed value (numbers stay
 * numeric, booleans stay boolean), so `Gt("$.priority", 5)` does numeric
 * comparison rather than lexicographic string comparison. Bind parameters of
 * type [String], [Int], [Long], [Float], [Double], [Boolean], or `null`.
 *
 * **Mutating-data caveat.** Keyset pagination over a filtered view is correct
 * for any single page, but rows whose match-status changes between page loads
 * (e.g. status field updated mid-scroll) may appear or disappear. This is
 * fundamental to keyset pagination over mutable data, not a bug.
 */
public sealed interface Filter {
    /**
     * Equality predicate: matches rows where `data_blob[path] = value`.
     *
     * If [value] is `null`, this renders as `IS NULL` so it behaves correctly
     * against missing or explicitly-null JSON fields (SQL `=` against NULL is
     * never true, even for NULL operands).
     */
    public data class Eq(public val path: String, public val value: Any?) : Filter

    /**
     * Inequality predicate: matches rows where `data_blob[path] != value`.
     *
     * If [value] is `null`, this renders as `IS NOT NULL` for the same reason
     * [Eq] flips to `IS NULL` — SQL `!=` against NULL is never true.
     */
    public data class Ne(public val path: String, public val value: Any?) : Filter

    /**
     * Greater-than predicate: matches rows where `data_blob[path] > value`.
     *
     * Numeric values use numeric comparison (because `json_extract` preserves
     * JSON types); string values use lexicographic comparison. [value] is
     * non-nullable because `column > NULL` is never true.
     */
    public data class Gt(public val path: String, public val value: Any) : Filter

    /**
     * Greater-than-or-equal predicate: matches rows where `data_blob[path] >= value`.
     *
     * See [Gt] for type-comparison semantics.
     */
    public data class Gte(public val path: String, public val value: Any) : Filter

    /**
     * Less-than predicate: matches rows where `data_blob[path] < value`.
     *
     * See [Gt] for type-comparison semantics.
     */
    public data class Lt(public val path: String, public val value: Any) : Filter

    /**
     * Less-than-or-equal predicate: matches rows where `data_blob[path] <= value`.
     *
     * See [Gt] for type-comparison semantics.
     */
    public data class Lte(public val path: String, public val value: Any) : Filter

    /**
     * Set-membership predicate: matches rows where `data_blob[path]` equals any
     * element of [values].
     *
     * An empty [values] list is logically false — the resulting query returns
     * no rows (rendered as `0` in SQL to avoid `IN ()` syntax errors).
     */
    public data class In(public val path: String, public val values: List<Any?>) : Filter

    /**
     * SQL `LIKE` predicate: matches rows where `data_blob[path] LIKE pattern`.
     *
     * Use `%` for any-string and `_` for any-char wildcards. Case sensitivity
     * follows SQLite's default (case-insensitive for ASCII, sensitive for the
     * rest of Unicode unless `PRAGMA case_sensitive_like=ON` is set).
     *
     * Operates on string values only. Comparing against a non-string JSON value
     * silently produces no matches.
     */
    public data class Like(public val path: String, public val pattern: String) : Filter

    /**
     * Null-or-missing predicate: matches rows where `data_blob[path]` is NULL
     * or the path does not exist in the document.
     *
     * `json_extract` returns SQL NULL for both cases, so [IsNull] cannot
     * distinguish "field is explicitly null" from "field is absent".
     */
    public data class IsNull(public val path: String) : Filter

    /**
     * Present-and-non-null predicate: matches rows where `data_blob[path]` is
     * not NULL and the path exists in the document.
     *
     * Inverse of [IsNull].
     */
    public data class IsNotNull(public val path: String) : Filter

    /**
     * Logical AND of [filters]. Matches rows that satisfy *every* sub-filter.
     *
     * An empty [filters] list is logically true — the resulting predicate
     * matches all rows (rendered as `1` in SQL).
     */
    public data class And(public val filters: List<Filter>) : Filter

    /**
     * Logical OR of [filters]. Matches rows that satisfy *any* sub-filter.
     *
     * An empty [filters] list is logically false — the resulting predicate
     * matches no rows (rendered as `0` in SQL).
     */
    public data class Or(public val filters: List<Filter>) : Filter

    /**
     * Logical negation. Matches rows that *do not* satisfy [filter].
     *
     * Note that NOT around a comparison against NULL stays NULL (per SQL
     * three-valued logic), which means rows where the path is missing/NULL
     * may not appear under negated predicates. Use [IsNull] / [IsNotNull]
     * for explicit null handling.
     */
    public data class Not(public val filter: Filter) : Filter

    public companion object {
        public fun eq(path: String, value: Any?): Filter = Eq(path, value)
        public fun ne(path: String, value: Any?): Filter = Ne(path, value)
        public fun gt(path: String, value: Any): Filter = Gt(path, value)
        public fun gte(path: String, value: Any): Filter = Gte(path, value)
        public fun lt(path: String, value: Any): Filter = Lt(path, value)
        public fun lte(path: String, value: Any): Filter = Lte(path, value)
        public fun isIn(path: String, values: List<Any?>): Filter = In(path, values)
        public fun like(path: String, pattern: String): Filter = Like(path, pattern)
        public fun isNull(path: String): Filter = IsNull(path)
        public fun isNotNull(path: String): Filter = IsNotNull(path)
        public fun and(vararg filters: Filter): Filter = And(filters.toList())
        public fun or(vararg filters: Filter): Filter = Or(filters.toList())
        public fun not(filter: Filter): Filter = Not(filter)
    }
}
