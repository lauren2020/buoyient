package com.les.databuoy

internal actual fun platformRegisterServices(services: Set<SyncableObjectService<*, *>>) {
    // No-op on JVM — background sync is not supported outside Android/iOS.
}
