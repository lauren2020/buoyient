package com.les.databuoy

actual fun createPlatformBackgroundRequestScheduler(): BackgroundRequestScheduler =
    IosBackgroundRequestScheduler()

class IosBackgroundRequestScheduler : BackgroundRequestScheduler {
    override fun scheduleRequest(
        httpRequest: HttpRequest,
        globalHeaders: List<Pair<String, String>>,
    ) {
        // TODO: Implement via NSURLSession background task or BGTaskScheduler
    }
}
