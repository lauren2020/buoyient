package com.les.databuoy

import com.les.databuoy.serviceconfigs.SyncableObjectRebaseHandler

public sealed class ResolveConflictResult<O : SyncableObject<O>> {
    public class Resolved<O : SyncableObject<O>>(public val resolvedData: O) : ResolveConflictResult<O>()
    public class RebaseConflict<O : SyncableObject<O>>(
        public val conflict: SyncableObjectRebaseHandler.FieldConflict<O>,
    ) : ResolveConflictResult<O>()
    public class Failed<O : SyncableObject<O>>(public val exception: Exception) : ResolveConflictResult<O>()
}
