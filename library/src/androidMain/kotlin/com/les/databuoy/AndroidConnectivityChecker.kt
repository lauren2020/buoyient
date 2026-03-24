package com.les.databuoy

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Android implementation of [ConnectivityChecker] backed by [ConnectivityManager].
 */
actual fun createPlatformConnectivityChecker(): ConnectivityChecker =
    AndroidConnectivityChecker(DataBuoyPlatformContext.appContext)

class AndroidConnectivityChecker(private val context: Context) : ConnectivityChecker {

    override fun isOnline(): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities =
            connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
