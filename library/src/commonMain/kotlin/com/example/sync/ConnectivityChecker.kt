package com.example.sync

/**
 * Platform-agnostic interface for checking network connectivity.
 * On Android, this wraps [ConnectivityManager]. On iOS, this wraps NWPathMonitor.
 */
interface ConnectivityChecker {
    fun isOnline(): Boolean
}

/**
 * Returns the platform-specific [ConnectivityChecker] implementation.
 * On Android this uses [ConnectivityManager]; on iOS this is a stub for NWPathMonitor.
 */
expect fun createPlatformConnectivityChecker(): ConnectivityChecker
