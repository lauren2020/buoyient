package com.les.buoyient.testing

import com.les.buoyient.utils.BuoyientLogger

/**
 * A [BuoyientLogger] that silently discards all log messages.
 * Useful in tests where log output is not needed.
 */
public object NoOpSyncLogger : BuoyientLogger {
    override fun d(tag: String, message: String) {}
    override fun w(tag: String, message: String) {}
    override fun e(tag: String, message: String, throwable: Throwable?) {}
}

/**
 * A [BuoyientLogger] that prints all log messages to stdout.
 * Useful for debugging tests or in manual testing scenarios.
 */
public object PrintSyncLogger : BuoyientLogger {
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
