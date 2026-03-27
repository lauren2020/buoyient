package com.elvdev.buoyient

import android.content.Context

/**
 * Internal holder for the application [Context], populated automatically
 * by [BuoyientInitializer] via `androidx.startup` before `Application.onCreate()`.
 */
public object BuoyientPlatformContext {
    public lateinit var appContext: Context
        private set

    public fun initialize(context: Context) {
        appContext = context.applicationContext
    }
}
