package com.les.databuoy

/**
 * Platform-agnostic interface for checking network connectivity.
 * On Android, this wraps [ConnectivityManager]. On iOS, this wraps NWPathMonitor.
 */
public interface ConnectivityChecker {
    public fun isOnline(): Boolean
}

/**
 * Returns the platform-specific [ConnectivityChecker] implementation.
 * On Android this uses [ConnectivityManager]; on iOS this is a stub for NWPathMonitor.
 */
public expect fun createPlatformConnectivityChecker(): ConnectivityChecker
