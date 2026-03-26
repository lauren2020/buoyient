package com.les.databuoy.testing

import com.les.databuoy.serviceconfigs.ConnectivityChecker

/**
 * A [ConnectivityChecker] with mutable state for controlling online/offline
 * behavior in tests. Flip [online] mid-test to simulate connectivity changes.
 *
 * ```kotlin
 * val checker = TestConnectivityChecker(online = true)
 * // ... perform online operations ...
 * checker.online = false
 * // ... now operations will follow the offline path ...
 * ```
 */
public class TestConnectivityChecker(
    public var online: Boolean = true,
) : ConnectivityChecker {
    override fun isOnline(): Boolean = online
}
