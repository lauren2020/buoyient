package com.les.databuoy

internal val registeredServices = mutableSetOf<SyncableObjectService<*, *>>()

internal actual fun platformRegisterServices(services: Set<SyncableObjectService<*, *>>) {
    registeredServices.clear()
    registeredServices.addAll(services)
}

/**
 * Trigger an immediate sync-up pass (e.g. when the app returns to
 * foreground or after a batch of offline writes).
 *
 * @param completion called with `true` when the queue is fully drained
 *   (or only blocked by conflicts), `false` if requests remain.
 */
fun DataBuoy.syncNow(completion: (Boolean) -> Unit = {}) {
    IosSyncRunner().performSync(completion)
}
