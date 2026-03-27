package com.les.databuoy.utils

import android.util.Log

/**
 * Android implementation of [DataBuoyLogger] backed by [android.util.Log].
 */
public actual fun createPlatformDataBuoyLogger(): DataBuoyLogger = AndroidDataBuoyLogger()

public class AndroidDataBuoyLogger : DataBuoyLogger {
    override fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun w(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }
}
