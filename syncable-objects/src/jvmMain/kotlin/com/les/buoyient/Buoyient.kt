package com.les.buoyient.globalconfigs

import com.les.buoyient.SyncableObjectService

internal actual fun platformRegisterServices(services: Set<SyncableObjectService<*, *>>) {
    // No-op on JVM — background sync is not supported outside Android/iOS.
}
