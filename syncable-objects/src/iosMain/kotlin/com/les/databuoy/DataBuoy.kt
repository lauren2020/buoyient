package com.les.databuoy.globalconfigs

import com.les.databuoy.SyncableObjectService

internal actual fun platformRegisterServices(services: Set<SyncableObjectService<*, *>>) {
    // Services are stored in the common DataBuoy.registeredServices.
    // No additional iOS-specific registration needed — IosSyncScheduleNotifier
    // uses SyncRunner which reads from the common registry.
}
