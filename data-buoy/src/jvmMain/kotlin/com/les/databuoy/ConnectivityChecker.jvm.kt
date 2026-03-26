package com.les.databuoy

public actual fun createPlatformConnectivityChecker(): ConnectivityChecker = object : ConnectivityChecker {
    override fun isOnline(): Boolean = true
}
