package com.example.sync

actual fun createPlatformSyncLogger(): SyncLogger = object : SyncLogger {
    override fun d(tag: String, message: String) { println("D/$tag: $message") }
    override fun w(tag: String, message: String) { println("W/$tag: $message") }
    override fun e(tag: String, message: String, throwable: Throwable?) {
        println("E/$tag: $message")
        throwable?.printStackTrace()
    }
}
