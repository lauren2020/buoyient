package com.les.databuoy

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

actual fun createPlatformBackgroundRequestScheduler(): BackgroundRequestScheduler =
    AndroidBackgroundRequestScheduler(DataBuoyPlatformContext.appContext)

class AndroidBackgroundRequestScheduler(private val context: Context) : BackgroundRequestScheduler {

    override fun scheduleRequest(
        httpRequest: HttpRequest,
        serviceHeaders: List<Pair<String, String>>,
    ) {
        val inputData = workDataOf(
            VoidByIdempotencyKeyWorker.KEY_REQUEST_JSON to httpRequest.toJson().toString(),
            VoidByIdempotencyKeyWorker.KEY_HEADERS_JSON to VoidByIdempotencyKeyWorker.serializeHeaders(serviceHeaders),
        )

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<VoidByIdempotencyKeyWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS,
            )
            .build()

        WorkManager.getInstance(context).enqueue(workRequest)

        Log.d(TAG, "Void-by-idempotency-key work enqueued")
    }

    companion object {
        private const val TAG = "BackgroundRequestScheduler"
    }
}
