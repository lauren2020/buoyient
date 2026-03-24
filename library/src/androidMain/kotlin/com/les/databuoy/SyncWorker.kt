package com.les.databuoy

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * A background [CoroutineWorker] that uploads locally-stored, unsynced
 * data objects to their respective remote APIs.
 *
 * This worker is scheduled by [SyncScheduler] with a
 * [androidx.work.NetworkType.CONNECTED] constraint so it only runs
 * when the device has network connectivity.
 *
 * The app module must register a [SyncServiceRegistryProvider] via
 * [registerServiceProvider] so the worker knows which services to sync.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val TAG = "TEST_THIS_SyncWorker"
        const val UNIQUE_WORK_NAME = "offline_sync_work"

        private var serviceProvider: SyncServiceRegistryProvider? = null

        /**
         * Register the app's [SyncServiceRegistryProvider] so the worker
         * can create the correct service instances at sync time.
         * Call this once during app startup (e.g. in Application.onCreate).
         */
        fun registerServiceProvider(provider: SyncServiceRegistryProvider) {
            serviceProvider = provider
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "SyncWorker started (runAttemptCount $runAttemptCount)")

        val provider = serviceProvider
        if (provider == null) {
            Log.e(TAG, "No SyncServiceRegistryProvider registered — cannot sync")
            return Result.failure()
        }

        val services = provider.createServices(applicationContext)

        return try {
            val database = createSyncDatabase()
            val coordinator = SyncUpCoordinator(
                participants = services,
                database = database,
                logger = createPlatformSyncLogger(),
            )
            val totalSynced = coordinator.syncUpAll()
            val status = DataBuoyStatus(database)
            val remainingPendingCount = status.pendingRequestCount.value
            val hasPendingConflicts = status.hasPendingConflicts.value

            Log.d(
                TAG,
                "SyncWorker finished: synced $totalSynced items, " +
                    "remainingPending=$remainingPendingCount, hasConflicts=$hasPendingConflicts"
            )

            // Report success only when the queue is drained, or when the remaining work
            // is blocked on a manual conflict resolution that retrying cannot fix.
            if (remainingPendingCount == 0 || hasPendingConflicts) {
                Result.success()
            } else {
                Log.w(TAG, "Pending requests remain after sync, scheduling retry")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "SyncWorker failed", e)
            if (runAttemptCount >= 10) {
                Log.e(TAG, "Max retries exceeded, giving up")
                Result.failure()
            } else {
                Result.retry()
            }
        } finally {
            services.forEach { it.close() }
        }
    }
}
