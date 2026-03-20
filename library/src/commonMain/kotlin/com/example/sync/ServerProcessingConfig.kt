package com.example.sync

interface ServerProcessingConfig<O : SyncableObject<O>> {
    val syncFetchConfig: SyncFetchConfig<O>
    val syncUpConfig: SyncUpConfig<O>
    val headers: List<Pair<String, String>>
}
