package com.les.buoyient.sync

import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSLog

public actual fun createPlatformSyncScheduleNotifier(): SyncScheduleNotifier = IosSyncScheduleNotifier()

/**
 * iOS implementation of [SyncScheduleNotifier] using BGTaskScheduler.
 *
 * Submits a [BGProcessingTaskRequest] that requires network connectivity.
 * The host app must:
 * 1. Add the task identifier to its Info.plist under `BGTaskSchedulerPermittedIdentifiers`.
 * 2. Call [IosSyncScheduleNotifier.registerHandler] once at app launch
 *    (e.g. in `application(_:didFinishLaunchingWithOptions:)`) to wire up
 *    the handler that performs the actual sync.
 */
public class IosSyncScheduleNotifier : SyncScheduleNotifier {

    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    override fun scheduleSyncIfNeeded() {
        val request = BGProcessingTaskRequest(TASK_IDENTIFIER)
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = false

        try {
            BGTaskScheduler.sharedScheduler.submitTaskRequest(request, null)
            NSLog("$TAG: Sync task submitted")
        } catch (e: Exception) {
            // BGTaskScheduler throws if the task identifier isn't registered
            // in Info.plist, or if called on a simulator. Both are expected
            // during development and safe to log-and-skip.
            NSLog("$TAG: Failed to submit sync task: ${e.message}")
        }
    }

    public companion object {
        private const val TAG = "IosSyncScheduleNotifier"

        /**
         * The BGTask identifier. The host app must include this value in its
         * Info.plist under the `BGTaskSchedulerPermittedIdentifiers` array.
         */
        public const val TASK_IDENTIFIER: String = "com.les.buoyient.sync"

        /**
         * Registers the BGTask handler. Call once at app launch.
         *
         * ```swift
         * // In AppDelegate.swift:
         * func application(_ application: UIApplication,
         *                  didFinishLaunchingWithOptions launchOptions: ...) -> Bool {
         *     IosSyncScheduleNotifier.Companion.shared.registerHandler()
         *     // ...
         * }
         * ```
         */
        public fun registerHandler() {
            BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(
                TASK_IDENTIFIER,
                usingQueue = null,
            ) { task ->
                SyncRunner.launchSyncUp { success ->
                    task?.setTaskCompletedWithSuccess(success)
                    // Re-schedule for next time
                    IosSyncScheduleNotifier().scheduleSyncIfNeeded()
                }
            }
            NSLog("$TAG: BGTask handler registered for $TASK_IDENTIFIER")
        }
    }
}
