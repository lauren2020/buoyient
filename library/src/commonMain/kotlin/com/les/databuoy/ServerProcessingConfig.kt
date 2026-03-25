package com.les.databuoy

interface ServerProcessingConfig<O : SyncableObject<O>> {
    /**
     * The configuration for how syncing server data down and upserting it to the local store
     * should be handled.
     */
    val syncFetchConfig: SyncFetchConfig<O>

    /**
     * The configuration for how syncing local data up to the server should be handled.
     */
    val syncUpConfig: SyncUpConfig<O>

    /**
     * Headers specific to this service, applied to every request it makes.
     *
     * For auth headers shared across all services, use [DataBuoy.globalHeaderProvider] instead.
     * At request time, headers are merged in order: global provider headers, then these
     * service headers, then per-request additional headers.
     */
    val serviceHeaders: List<Pair<String, String>>
}
