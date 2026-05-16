package com.elvdev.buoyient.managers

import com.elvdev.buoyient.datatypes.Filter

/**
 * Renders a [Filter] tree to a (sql_fragment, params) pair using `json_extract`
 * against the `data_blob` column.
 *
 * The fragment is parenthesized so it composes safely inside a larger WHERE clause.
 * Path strings are bound as parameters (not interpolated) to keep the rendered SQL
 * stable for prepared-statement caching across different paths.
 */
internal fun Filter.toSql(): Pair<String, List<Any?>> = when (this) {
    is Filter.Eq -> {
        if (value == null) {
            "json_extract(data_blob, ?) IS NULL" to listOf<Any?>(path)
        } else {
            "json_extract(data_blob, ?) = ?" to listOf(path, value)
        }
    }
    is Filter.Ne -> {
        if (value == null) {
            "json_extract(data_blob, ?) IS NOT NULL" to listOf<Any?>(path)
        } else {
            "json_extract(data_blob, ?) != ?" to listOf(path, value)
        }
    }
    is Filter.Gt -> "json_extract(data_blob, ?) > ?" to listOf(path, value)
    is Filter.Gte -> "json_extract(data_blob, ?) >= ?" to listOf(path, value)
    is Filter.Lt -> "json_extract(data_blob, ?) < ?" to listOf(path, value)
    is Filter.Lte -> "json_extract(data_blob, ?) <= ?" to listOf(path, value)
    is Filter.Like -> "json_extract(data_blob, ?) LIKE ?" to listOf(path, pattern)
    is Filter.IsNull -> "json_extract(data_blob, ?) IS NULL" to listOf<Any?>(path)
    is Filter.IsNotNull -> "json_extract(data_blob, ?) IS NOT NULL" to listOf<Any?>(path)
    is Filter.In -> {
        // Empty IN-list is logically false. Render as `0` to avoid `IN ()` syntax errors.
        if (values.isEmpty()) {
            "0" to emptyList()
        } else {
            val placeholders = values.joinToString(",") { "?" }
            "json_extract(data_blob, ?) IN ($placeholders)" to (listOf<Any?>(path) + values)
        }
    }
    is Filter.And -> {
        if (filters.isEmpty()) {
            "1" to emptyList()
        } else {
            val parts = filters.map { it.toSql() }
            parts.joinToString(" AND ", "(", ")") { it.first } to parts.flatMap { it.second }
        }
    }
    is Filter.Or -> {
        if (filters.isEmpty()) {
            "0" to emptyList()
        } else {
            val parts = filters.map { it.toSql() }
            parts.joinToString(" OR ", "(", ")") { it.first } to parts.flatMap { it.second }
        }
    }
    is Filter.Not -> {
        val (clause, params) = filter.toSql()
        "NOT $clause" to params
    }
}

/**
 * Walks a [Filter] tree and returns every distinct JSON path it references.
 * Used to drive expression-index creation and to surface "scan-warning" diagnostics
 * for un-indexed paths.
 */
internal fun Filter.collectPaths(): Set<String> {
    val out = mutableSetOf<String>()
    collectPathsInto(out)
    return out
}

private fun Filter.collectPathsInto(out: MutableSet<String>) {
    when (this) {
        is Filter.Eq -> out.add(path)
        is Filter.Ne -> out.add(path)
        is Filter.Gt -> out.add(path)
        is Filter.Gte -> out.add(path)
        is Filter.Lt -> out.add(path)
        is Filter.Lte -> out.add(path)
        is Filter.Like -> out.add(path)
        is Filter.IsNull -> out.add(path)
        is Filter.IsNotNull -> out.add(path)
        is Filter.In -> out.add(path)
        is Filter.And -> filters.forEach { it.collectPathsInto(out) }
        is Filter.Or -> filters.forEach { it.collectPathsInto(out) }
        is Filter.Not -> filter.collectPathsInto(out)
    }
}
