package com.elvdev.buoyient.globalconfigs

import app.cash.sqldelight.db.SqlDriver
import com.elvdev.buoyient.db.SyncDatabase

/**
 * Pairs a [SyncDatabase] with the [SqlDriver] that backs it.
 *
 * Most code only needs the [SyncDatabase] — the [SqlDriver] is exposed because
 * dynamic-SQL features (e.g. [com.elvdev.buoyient.SyncableObjectService.loadPage]
 * with a filter) need raw driver access for queries that SQLDelight's static
 * codegen cannot represent.
 *
 * Treat the two fields as a unit: never mix a database from one handle with a
 * driver from another — they must be the same underlying connection.
 */
public data class SyncDatabaseHandle(
    public val database: SyncDatabase,
    public val driver: SqlDriver,
)
