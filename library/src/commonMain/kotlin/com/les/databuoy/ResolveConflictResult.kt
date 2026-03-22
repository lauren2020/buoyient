package com.les.databuoy

sealed class ResolveConflictResult<O : SyncableObject<O>> {
    class Resolved<O : SyncableObject<O>>(val resolvedData: O) : ResolveConflictResult<O>()
    class RebaseConflict<O : SyncableObject<O>>(
        val conflict: SyncableObjectMergeHandler.FieldConflict<O>,
    ) : ResolveConflictResult<O>()
    class Failed<O : SyncableObject<O>>(val exception: Exception) : ResolveConflictResult<O>()
}
