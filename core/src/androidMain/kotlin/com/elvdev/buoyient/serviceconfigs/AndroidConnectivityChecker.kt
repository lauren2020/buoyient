package com.elvdev.buoyient.serviceconfigs

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.elvdev.buoyient.BuoyientPlatformContext

/**
 * Android implementation of [ConnectivityChecker] backed by [ConnectivityManager].
 */
public actual fun createPlatformConnectivityChecker(): ConnectivityChecker =
    AndroidConnectivityChecker(BuoyientPlatformContext.appContext)

public class AndroidConnectivityChecker(private val context: Context) : ConnectivityChecker {

    @SuppressLint("MissingPermission") // Permission declared by the consuming app
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
