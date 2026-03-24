package com.les.databuoy

import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_queue_create

actual fun createPlatformConnectivityChecker(): ConnectivityChecker = IosConnectivityChecker()

class IosConnectivityChecker : ConnectivityChecker {

    private val monitor = nw_path_monitor_create()
    private val queue = dispatch_queue_create("com.les.databuoy.connectivity", null)

    @Volatile
    private var currentStatus: Boolean = true

    init {
        nw_path_monitor_set_update_handler(monitor) { path ->
            currentStatus = nw_path_get_status(path) == nw_path_status_satisfied
        }
        nw_path_monitor_set_queue(monitor, queue)
        nw_path_monitor_start(monitor)
    }

    override fun isOnline(): Boolean = currentStatus

    fun cancel() {
        nw_path_monitor_cancel(monitor)
    }
}
