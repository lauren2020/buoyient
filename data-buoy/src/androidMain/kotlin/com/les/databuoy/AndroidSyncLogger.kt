package com.les.databuoy

import android.util.Log

/**
 * Android implementation of [SyncLogger] backed by [android.util.Log].
 */
public actual fun createPlatformSyncLogger(): SyncLogger = AndroidSyncLogger()

public class AndroidSyncLogger : SyncLogger {
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
