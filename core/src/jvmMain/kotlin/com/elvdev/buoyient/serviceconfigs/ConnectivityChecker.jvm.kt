package com.elvdev.buoyient.serviceconfigs

public actual fun createPlatformConnectivityChecker(): ConnectivityChecker = object :
    ConnectivityChecker {
    override fun isOnline(): Boolean = true
}
