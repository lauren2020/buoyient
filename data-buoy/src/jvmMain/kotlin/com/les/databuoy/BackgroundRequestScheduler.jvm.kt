package com.les.databuoy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

public actual fun createPlatformBackgroundRequestScheduler(): BackgroundRequestScheduler =
    object : BackgroundRequestScheduler {
        override fun scheduleRequest(
            httpRequest: HttpRequest,
            serviceHeaders: List<Pair<String, String>>,
        ) {
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                val registryHeaders = GlobalHeaderProviderRegistry.provider?.headers().orEmpty()
                val serverManager = ServerManager(
                    serviceBaseHeaders = registryHeaders + serviceHeaders,
                )
                try {
                    serverManager.sendRequest(httpRequest)
                } finally {
                    serverManager.close()
                }
            }
        }
    }
