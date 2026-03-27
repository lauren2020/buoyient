package com.les.databuoy

import android.content.Context

/**
 * Internal holder for the application [Context], populated automatically
 * by [DataBuoyInitializer] via `androidx.startup` before `Application.onCreate()`.
 */
public object DataBuoyPlatformContext {
    public lateinit var appContext: Context
        private set

    public fun initialize(context: Context) {
        appContext = context.applicationContext
    }
}
