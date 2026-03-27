package com.les.buoyient.globalconfigs

import com.les.buoyient.SyncableObjectService

internal actual fun platformRegisterServices(services: Set<SyncableObjectService<*, *>>) {
    // Services are stored in the common Buoyient.registeredServices.
    // No additional iOS-specific registration needed — IosSyncScheduleNotifier
    // uses SyncRunner which reads from the common registry.
}
