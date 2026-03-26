package com.les.databuoy.serviceconfigs

public actual fun createPlatformConnectivityChecker(): ConnectivityChecker = object :
    ConnectivityChecker {
    override fun isOnline(): Boolean = true
}
