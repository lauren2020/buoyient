package com.les.databuoy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

actual fun createPlatformBackgroundRequestScheduler(): BackgroundRequestScheduler =
    object : BackgroundRequestScheduler {
        override fun scheduleRequest(
            httpRequest: HttpRequest,
            globalHeaders: List<Pair<String, String>>,
        ) {
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                val serverManager = ServerManager(
                    serviceBaseHeaders = globalHeaders,
                    logger = createPlatformSyncLogger(),
                )
                try {
                    serverManager.sendRequest(httpRequest)
                } finally {
                    serverManager.close()
                }
            }
        }
    }
