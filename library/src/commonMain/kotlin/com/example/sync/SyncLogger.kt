package com.example.sync

/**
 * Platform-agnostic logging interface for the sync SDK.
 *
 * Implementations map to the host platform's logging facility
 * (e.g., `android.util.Log` on Android, `NSLog`/`os_log` on iOS).
 */
interface SyncLogger {
    fun d(tag: String, message: String)
    fun w(tag: String, message: String)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}

/**
 * Returns the platform-specific [SyncLogger] implementation.
 * On Android this delegates to `android.util.Log`; on iOS to `println`.
 */
expect fun createPlatformSyncLogger(): SyncLogger
