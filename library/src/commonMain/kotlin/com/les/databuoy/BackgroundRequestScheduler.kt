package com.les.databuoy

/**
 * Platform-agnostic interface for scheduling fire-and-forget HTTP requests
 * that should be executed in the background with retry capabilities.
 *
 * On Android, this schedules a dedicated WorkManager job with network constraints.
 * On iOS, this is a stub pending BGTaskScheduler implementation.
 */
interface BackgroundRequestScheduler {
    fun scheduleRequest(
        httpRequest: HttpRequest,
        globalHeaders: List<Pair<String, String>>,
    )
}

/**
 * Returns the platform-specific [BackgroundRequestScheduler] implementation.
 */
expect fun createPlatformBackgroundRequestScheduler(): BackgroundRequestScheduler
