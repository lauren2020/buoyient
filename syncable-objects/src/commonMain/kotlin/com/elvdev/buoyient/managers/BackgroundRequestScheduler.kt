package com.elvdev.buoyient.managers

import com.elvdev.buoyient.datatypes.HttpRequest

/**
 * Platform-agnostic interface for scheduling fire-and-forget HTTP requests
 * that should be executed in the background with retry capabilities.
 *
 * On Android, this schedules a dedicated WorkManager job with network constraints.
 * On iOS, this is a stub pending BGTaskScheduler implementation.
 */
public interface BackgroundRequestScheduler {
    public fun scheduleRequest(
        httpRequest: HttpRequest,
        serviceHeaders: List<Pair<String, String>>,
    )
}

/**
 * Returns the platform-specific [BackgroundRequestScheduler] implementation.
 */
public expect fun createPlatformBackgroundRequestScheduler(): BackgroundRequestScheduler
