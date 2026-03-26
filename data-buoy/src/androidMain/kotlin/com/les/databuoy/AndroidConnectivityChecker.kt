package com.les.databuoy.publicconfigs

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.les.databuoy.DataBuoyPlatformContext

/**
 * Android implementation of [com.les.databuoy.publicconfigs.ConnectivityChecker] backed by [ConnectivityManager].
 */
public actual fun createPlatformConnectivityChecker(): ConnectivityChecker =
    AndroidConnectivityChecker(DataBuoyPlatformContext.appContext)

public class AndroidConnectivityChecker(private val context: Context) : ConnectivityChecker {

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
