package com.elvdev.buoyient.hilt

import android.content.Context
import androidx.startup.Initializer
import com.elvdev.buoyient.globalconfigs.Buoyient
import com.elvdev.buoyient.BuoyientInitializer
import com.elvdev.buoyient.sync.SyncDriver
import com.elvdev.buoyient.SyncServiceRegistryProvider
import com.elvdev.buoyient.globalconfigs.registerServiceProvider
import dagger.hilt.android.EntryPointAccessors

/**
 * Auto-initializer that bridges Hilt's dependency graph with buoyient's
 * service registration.
 *
 * Registered via `androidx.startup` in this module's `AndroidManifest.xml`
 * and depends on [BuoyientInitializer] so that the platform context is
 * available before this initializer runs.
 *
 * Rather than eagerly resolving drivers (which would require the Hilt
 * [SingletonComponent] to be ready), this registers a lazy
 * [SyncServiceRegistryProvider] that accesses the Hilt entry point when
 * [com.elvdev.buoyient.SyncWorker] actually runs — at which point the
 * application is fully initialized and Hilt's component graph is available.
 */
public class BuoyientHiltInitializer : Initializer<Unit> {

    override fun create(context: Context) {
        // Register a lazy provider — drivers are resolved from Hilt only
        // when SyncWorker.doWork() calls createDrivers(), which is always
        // after Application.onCreate() has completed.
        Buoyient.registerServiceProvider(object : SyncServiceRegistryProvider {
            override fun createDrivers(context: Context): List<SyncDriver<*, *>> {
                val entryPoint = EntryPointAccessors.fromApplication(
                    context,
                    BuoyientEntryPoint::class.java,
                )
                return entryPoint.syncDrivers().toList()
            }
        })
    }

    override fun dependencies(): List<Class<out Initializer<*>>> =
        listOf(BuoyientInitializer::class.java)
}
