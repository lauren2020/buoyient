package com.example.sync

actual fun createPlatformConnectivityChecker(): ConnectivityChecker = object : ConnectivityChecker {
    override fun isOnline(): Boolean = true
}
