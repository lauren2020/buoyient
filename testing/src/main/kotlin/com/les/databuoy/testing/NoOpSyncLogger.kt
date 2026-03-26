package com.les.databuoy.testing

import com.les.databuoy.SyncLogger

/**
 * A [SyncLogger] that silently discards all log messages.
 * Useful in tests where log output is not needed.
 */
public object NoOpSyncLogger : SyncLogger {
    override fun d(tag: String, message: String) {}
    override fun w(tag: String, message: String) {}
    override fun e(tag: String, message: String, throwable: Throwable?) {}
}

/**
 * A [SyncLogger] that prints all log messages to stdout.
 * Useful for debugging tests or in manual testing scenarios.
 */
public object PrintSyncLogger : SyncLogger {
    override fun d(tag: String, message: String) {
        println("D/$tag: $message")
    }

    override fun w(tag: String, message: String) {
        println("W/$tag: $message")
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        println("E/$tag: $message")
        throwable?.printStackTrace()
    }
}
