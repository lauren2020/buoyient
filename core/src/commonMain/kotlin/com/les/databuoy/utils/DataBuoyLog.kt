package com.les.databuoy.utils

import kotlin.concurrent.Volatile

/**
 * Process-wide logger for the data-buoy SDK.
 *
 * Every internal component logs through this singleton. By default it
 * delegates to the platform logger ([createPlatformDataBuoyLogger]), but
 * callers can swap the backing [logger] at startup — for example,
 * `DataBuoyLog.logger = PrintDataBuoyLogger` in mock mode or tests.
 */
public object DataBuoyLog : DataBuoyLogger {
    @Volatile
    public var logger: DataBuoyLogger = createPlatformDataBuoyLogger()

    override fun d(tag: String, message: String): Unit = logger.d(tag, message)
    override fun w(tag: String, message: String): Unit = logger.w(tag, message)
    override fun e(tag: String, message: String, throwable: Throwable?): Unit = logger.e(tag, message, throwable)
}
