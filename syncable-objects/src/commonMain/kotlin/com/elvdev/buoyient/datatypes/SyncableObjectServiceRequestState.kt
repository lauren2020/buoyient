package com.elvdev.buoyient.datatypes

/**
 * State emitted by the flow-based service operations
 * ([com.elvdev.buoyient.SyncableObjectService.createWithFlow], [com.elvdev.buoyient.SyncableObjectService.updateWithFlow],
 * [com.elvdev.buoyient.SyncableObjectService.voidWithFlow]).
 *
 * Collect the returned [kotlinx.coroutines.flow.Flow] to observe the request lifecycle:
 * a [Loading] emission followed by a terminal [Result].
 *
 * @param O the domain model type that implements [com.elvdev.buoyient.SyncableObject].
 */
public sealed class SyncableObjectServiceRequestState<O> {
    /** The request is in progress. */
    public class Loading<O> : SyncableObjectServiceRequestState<O>()

    /**
     * The request completed (successfully or not).
     *
     * @property response the final [SyncableObjectServiceResponse] describing the outcome.
     */
    public class Result<O>(public val response: SyncableObjectServiceResponse<O>) : SyncableObjectServiceRequestState<O>()
}