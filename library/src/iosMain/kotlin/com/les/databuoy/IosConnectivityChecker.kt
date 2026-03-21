package com.les.databuoy

actual fun createPlatformConnectivityChecker(): ConnectivityChecker = IosConnectivityChecker()

class IosConnectivityChecker : ConnectivityChecker {
    override fun isOnline(): Boolean {
        // TODO: Implement using NWPathMonitor
        return true
    }
}
