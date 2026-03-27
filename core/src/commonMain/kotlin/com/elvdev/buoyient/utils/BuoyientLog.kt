package com.elvdev.buoyient.utils

import kotlin.concurrent.Volatile

/**
 * Process-wide logger for the buoyient SDK.
 *
 * Every internal component logs through this singleton. By default it
 * delegates to the platform logger ([createPlatformBuoyientLogger]), but
 * callers can swap the backing [logger] at startup — for example,
 * `BuoyientLog.logger = PrintBuoyientLogger` in mock mode or tests.
 */
public object BuoyientLog : BuoyientLogger {
    @Volatile
    public var logger: BuoyientLogger = createPlatformBuoyientLogger()

    override fun d(tag: String, message: String): Unit = logger.d(tag, message)
    override fun w(tag: String, message: String): Unit = logger.w(tag, message)
    override fun e(tag: String, message: String, throwable: Throwable?): Unit = logger.e(tag, message, throwable)
}
