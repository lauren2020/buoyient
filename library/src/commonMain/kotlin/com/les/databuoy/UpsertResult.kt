package com.les.databuoy

sealed class UpsertResult {
    object CleanUpsert : UpsertResult()
    object MergedUpsert : UpsertResult()
    object ConflictFailure : UpsertResult()
}
