package com.les.databuoy.utils

/**
 * Process-wide logger for the data-buoy sync engine.
 *
 * Every internal component logs through this singleton. By default it
 * delegates to the platform logger ([createPlatformSyncLogger]), but
 * callers can swap the backing [logger] at startup — for example,
 * `SyncLog.logger = PrintSyncLogger` in mock mode or tests.
 */
public object SyncLog : SyncLogger {
    @Volatile
    public var logger: SyncLogger = createPlatformSyncLogger()

    override fun d(tag: String, message: String): Unit = logger.d(tag, message)
    override fun w(tag: String, message: String): Unit = logger.w(tag, message)
    override fun e(tag: String, message: String, throwable: Throwable?): Unit = logger.e(tag, message, throwable)
}
