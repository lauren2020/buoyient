package com.example.sync

import android.content.Context

/**
 * Internal holder for the application [Context], populated automatically
 * by [DataBuoyInitializer] via `androidx.startup` before `Application.onCreate()`.
 */
internal object DataBuoyPlatformContext {
    lateinit var appContext: Context
        private set

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }
}
