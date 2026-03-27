package com.elvdev.buoyient.datatypes

import com.elvdev.buoyient.SyncableObject
import com.elvdev.buoyient.serviceconfigs.SyncableObjectRebaseHandler

public sealed class ResolveConflictResult<O : SyncableObject<O>> {
    public class Resolved<O : SyncableObject<O>>(public val resolvedData: O) : ResolveConflictResult<O>()
    public class RebaseConflict<O : SyncableObject<O>>(
        public val conflict: SyncableObjectRebaseHandler.FieldConflict<O>,
    ) : ResolveConflictResult<O>()
    public class Failed<O : SyncableObject<O>>(public val exception: Exception) : ResolveConflictResult<O>()
}
