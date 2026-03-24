package com.les.databuoy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import platform.Foundation.NSLog

actual fun createPlatformBackgroundRequestScheduler(): BackgroundRequestScheduler =
    IosBackgroundRequestScheduler()

/**
 * iOS implementation of [BackgroundRequestScheduler].
 *
 * Fires the HTTP request immediately on a background coroutine using the
 * shared Ktor [ServerManager]. Unlike Android's WorkManager, iOS does not
 * have a general-purpose persistent work queue for arbitrary HTTP requests.
 * NSURLSession background sessions only support upload/download tasks (not
 * arbitrary request bodies), and BGTaskScheduler requires per-app
 * entitlement configuration that a library cannot assume.
 *
 * This approach mirrors how the sync engine already works: if the request
 * fails due to a connection error, the pending request remains in the
 * SQLite queue and will be retried on the next sync-up pass (triggered
 * by [IosSyncScheduleNotifier]).
 */
class IosBackgroundRequestScheduler : BackgroundRequestScheduler {

    private val scope = CoroutineScope(Dispatchers.Default)
    private val logger = createPlatformSyncLogger()

    override fun scheduleRequest(
        httpRequest: HttpRequest,
        globalHeaders: List<Pair<String, String>>,
    ) {
        scope.launch {
            val serverManager = ServerManager(
                serviceBaseHeaders = globalHeaders,
                logger = logger,
            )
            try {
                when (val response = serverManager.sendRequest(httpRequest)) {
                    is ServerManager.ServerManagerResponse.ServerResponse -> {
                        logger.d(TAG, "Background request completed (${response.statusCode})")
                    }
                    is ServerManager.ServerManagerResponse.ConnectionError -> {
                        logger.w(TAG, "Background request failed due to connection error — will retry on next sync-up")
                    }
                }
            } catch (e: Exception) {
                logger.e(TAG, "Background request failed unexpectedly", e)
            } finally {
                serverManager.close()
            }
        }
    }

    companion object {
        private const val TAG = "IosBackgroundRequestScheduler"
    }
}
