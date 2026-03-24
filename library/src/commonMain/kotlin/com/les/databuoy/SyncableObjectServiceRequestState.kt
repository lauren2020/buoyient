package com.les.databuoy

sealed class SyncableObjectServiceRequestState<O> {
    class Loading<O> : SyncableObjectServiceRequestState<O>()
    class Result<O>(val response: SyncableObjectServiceResponse<O>) : SyncableObjectServiceRequestState<O>()
}
