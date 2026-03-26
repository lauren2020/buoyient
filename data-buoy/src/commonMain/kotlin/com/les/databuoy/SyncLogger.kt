package com.les.databuoy

/**
 * Platform-agnostic logging interface for the sync SDK.
 *
 * Implementations map to the host platform's logging facility
 * (e.g., `android.util.Log` on Android, `NSLog`/`os_log` on iOS).
 */
public interface SyncLogger {
    public fun d(tag: String, message: String)
    public fun w(tag: String, message: String)
    public fun e(tag: String, message: String, throwable: Throwable? = null)
}

/**
 * Returns the platform-specific [SyncLogger] implementation.
 * On Android this delegates to `android.util.Log`; on iOS to `println`.
 */
public expect fun createPlatformSyncLogger(): SyncLogger
