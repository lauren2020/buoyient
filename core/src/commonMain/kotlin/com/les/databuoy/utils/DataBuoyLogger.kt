package com.les.databuoy.utils

/**
 * Platform-agnostic logging interface for the data-buoy SDK.
 *
 * Implementations map to the host platform's logging facility
 * (e.g., `android.util.Log` on Android, `NSLog`/`os_log` on iOS).
 */
public interface DataBuoyLogger {
    public fun d(tag: String, message: String)
    public fun w(tag: String, message: String)
    public fun e(tag: String, message: String, throwable: Throwable? = null)
}

/**
 * Returns the platform-specific [DataBuoyLogger] implementation.
 * On Android this delegates to `android.util.Log`; on iOS to `println`.
 */
public expect fun createPlatformDataBuoyLogger(): DataBuoyLogger
