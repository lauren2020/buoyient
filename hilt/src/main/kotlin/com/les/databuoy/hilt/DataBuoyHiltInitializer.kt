package com.les.databuoy.hilt

import android.content.Context
import androidx.startup.Initializer
import com.les.databuoy.DataBuoy
import com.les.databuoy.DataBuoyInitializer
import com.les.databuoy.SyncServiceRegistryProvider
import com.les.databuoy.SyncableObjectService
import dagger.hilt.android.EntryPointAccessors

/**
 * Auto-initializer that bridges Hilt's dependency graph with data-buoy's
 * service registration.
 *
 * Registered via `androidx.startup` in this module's `AndroidManifest.xml`
 * and depends on [DataBuoyInitializer] so that the platform context is
 * available before this initializer runs.
 *
 * Rather than eagerly resolving services (which would require the Hilt
 * [SingletonComponent] to be ready), this registers a lazy
 * [SyncServiceRegistryProvider] that accesses the Hilt entry point when
 * [com.les.databuoy.SyncWorker] actually runs — at which point the
 * application is fully initialized and Hilt's component graph is available.
 */
class DataBuoyHiltInitializer : Initializer<Unit> {

    override fun create(context: Context) {
        // Register a lazy provider — services are resolved from Hilt only
        // when SyncWorker.doWork() calls createServices(), which is always
        // after Application.onCreate() has completed.
        DataBuoy.registerServiceProvider(object : SyncServiceRegistryProvider {
            override fun createServices(context: Context): List<SyncableObjectService<*, *>> {
                val entryPoint = EntryPointAccessors.fromApplication(
                    context,
                    DataBuoyEntryPoint::class.java,
                )
                return entryPoint.syncServices().toList()
            }
        })
    }

    override fun dependencies(): List<Class<out Initializer<*>>> =
        listOf(DataBuoyInitializer::class.java)
}
