package com.example.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Utility for scheduling the [SyncWorker] via WorkManager.
 *
 * Call [scheduleSyncIfNeeded] whenever offline data is stored so that
 * a background sync is attempted as soon as connectivity returns.
 */
object SyncScheduler {

    private const val TAG = "SyncScheduler"

    /**
     * Enqueues a [OneTimeWorkRequest] that will run the [SyncWorker] once
     * the device has a network connection.
     *
     * Uses [ExistingWorkPolicy.KEEP] so that multiple offline writes
     * do not duplicate enqueued work — only one sync pass is needed.
     */
    fun scheduleSyncIfNeeded(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS,
            )
            .addTag(SyncWorker.TAG)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                SyncWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                syncRequest,
            )

        Log.d(TAG, "Sync work enqueued (KEEP policy)")
    }
}
