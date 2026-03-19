package com.example.sync

actual fun createPlatformConnectivityChecker(): ConnectivityChecker = IosConnectivityChecker()

class IosConnectivityChecker : ConnectivityChecker {
    override fun isOnline(): Boolean {
        // TODO: Implement using NWPathMonitor
        return true
    }
}
