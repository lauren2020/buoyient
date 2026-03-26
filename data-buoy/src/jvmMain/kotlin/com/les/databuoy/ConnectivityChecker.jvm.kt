package com.les.databuoy.publicconfigs

public actual fun createPlatformConnectivityChecker(): ConnectivityChecker = object :
    ConnectivityChecker {
    override fun isOnline(): Boolean = true
}
