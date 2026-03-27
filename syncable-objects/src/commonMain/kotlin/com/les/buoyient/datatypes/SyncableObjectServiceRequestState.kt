package com.les.buoyient.datatypes

/**
 * State emitted by the flow-based service operations
 * ([com.les.buoyient.SyncableObjectService.createWithFlow], [com.les.buoyient.SyncableObjectService.updateWithFlow],
 * [com.les.buoyient.SyncableObjectService.voidWithFlow]).
 *
 * Collect the returned [kotlinx.coroutines.flow.Flow] to observe the request lifecycle:
 * a [Loading] emission followed by a terminal [Result].
 *
 * @param O the domain model type that implements [com.les.buoyient.SyncableObject].
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