package com.elvdev.buoyient.utils

/**
 * Platform-agnostic logging interface for the buoyient SDK.
 *
 * Implementations map to the host platform's logging facility
 * (e.g., `android.util.Log` on Android, `NSLog`/`os_log` on iOS).
 */
public interface BuoyientLogger {
    public fun d(tag: String, message: String)
    public fun w(tag: String, message: String)
    public fun e(tag: String, message: String, throwable: Throwable? = null)
}

/**
 * Returns the platform-specific [BuoyientLogger] implementation.
 * On Android this delegates to `android.util.Log`; on iOS to `println`.
 */
public expect fun createPlatformBuoyientLogger(): BuoyientLogger
