package com.elvdev.buoyient.globalconfigs

import com.elvdev.buoyient.SyncableObjectService

internal actual fun platformRegisterServices(services: Set<SyncableObjectService<*, *>>) {
    // No-op on JVM — background sync is not supported outside Android/iOS.
}
