package com.les.buoyient.utils

import android.util.Log

/**
 * Android implementation of [BuoyientLogger] backed by [android.util.Log].
 */
public actual fun createPlatformBuoyientLogger(): BuoyientLogger = AndroidBuoyientLogger()

public class AndroidBuoyientLogger : BuoyientLogger {
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
