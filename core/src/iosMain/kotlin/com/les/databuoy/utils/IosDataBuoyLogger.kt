package com.les.databuoy.utils

import platform.Foundation.NSLog

public actual fun createPlatformDataBuoyLogger(): DataBuoyLogger = IosDataBuoyLogger()

public class IosDataBuoyLogger : DataBuoyLogger {
    override fun d(tag: String, message: String) {
        NSLog("D/%s: %s", tag, message)
    }

    override fun w(tag: String, message: String) {
        NSLog("W/%s: %s", tag, message)
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        NSLog("E/%s: %s", tag, message)
        throwable?.let { NSLog("%s", it.stackTraceToString()) }
    }
}
