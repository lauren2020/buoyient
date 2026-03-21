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
     * The headers that should be applied to every request this service makes.
     */
    val globalHeaders: List<Pair<String, String>>
}
