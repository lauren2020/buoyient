package com.les.databuoy.globalconfigs

import com.les.databuoy.SyncableObjectService

internal actual fun platformRegisterServices(services: Set<SyncableObjectService<*, *>>) {
    // No-op on JVM — background sync is not supported outside Android/iOS.
}
