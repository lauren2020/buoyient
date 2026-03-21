package com.les.databuoy

actual fun createPlatformConnectivityChecker(): ConnectivityChecker = object : ConnectivityChecker {
    override fun isOnline(): Boolean = true
}
