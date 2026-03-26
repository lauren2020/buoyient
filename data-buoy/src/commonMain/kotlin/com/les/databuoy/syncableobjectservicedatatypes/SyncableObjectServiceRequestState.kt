package com.les.databuoy.syncableobjectservicedatatypes

/**
 * State emitted by the flow-based service operations
 * ([com.les.databuoy.SyncableObjectService.createWithFlow], [com.les.databuoy.SyncableObjectService.updateWithFlow],
 * [com.les.databuoy.SyncableObjectService.voidWithFlow]).
 *
 * Collect the returned [kotlinx.coroutines.flow.Flow] to observe the request lifecycle:
 * a [Loading] emission followed by a terminal [Result].
 *
 * @param O the domain model type that implements [com.les.databuoy.SyncableObject].
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