package com.les.databuoy

public sealed class UpsertResult {
    public object CleanUpsert : UpsertResult()
    public object MergedUpsert : UpsertResult()
    public object ConflictFailure : UpsertResult()
}
