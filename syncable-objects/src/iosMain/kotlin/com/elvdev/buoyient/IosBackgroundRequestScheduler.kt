package com.elvdev.buoyient.managers

import com.elvdev.buoyient.globalconfigs.GlobalHeaderProviderRegistry
import com.elvdev.buoyient.datatypes.HttpRequest
import com.elvdev.buoyient.utils.BuoyientLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import platform.Foundation.NSLog

public actual fun createPlatformBackgroundRequestScheduler(): BackgroundRequestScheduler =
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
public class IosBackgroundRequestScheduler : BackgroundRequestScheduler {

    private val scope = CoroutineScope(Dispatchers.Default)

    override fun scheduleRequest(
        httpRequest: HttpRequest,
        serviceHeaders: List<Pair<String, String>>,
    ) {
        scope.launch {
            val registryHeaders = GlobalHeaderProviderRegistry.provider?.headers().orEmpty()
            val serverManager = ServerManager(
                serviceBaseHeaders = registryHeaders + serviceHeaders,
            )
            try {
                when (val response = serverManager.sendRequest(httpRequest)) {
                    is ServerManager.ServerManagerResponse.Success -> {
                        BuoyientLog.d(TAG, "Background request completed (${response.statusCode})")
                    }
                    is ServerManager.ServerManagerResponse.Failed,
                    is ServerManager.ServerManagerResponse.ServerError -> {
                        BuoyientLog.w(TAG, "Background request failed — will retry on next sync-up")
                    }
                    is ServerManager.ServerManagerResponse.ConnectionError,
                    is ServerManager.ServerManagerResponse.RequestTimedOut -> {
                        BuoyientLog.w(TAG, "Background request failed due to connection error — will retry on next sync-up")
                    }
                }
            } catch (e: Exception) {
                BuoyientLog.e(TAG, "Background request failed unexpectedly", e)
            } finally {
                serverManager.close()
            }
        }
    }

    public companion object {
        private const val TAG = "IosBackgroundRequestScheduler"
    }
}
