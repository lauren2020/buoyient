package com.les.databuoy.hilt

import com.les.databuoy.SyncDriver
import dagger.Module
import dagger.multibindings.Multibinds
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt entry point that exposes the app's [SyncDriver] set to the
 * data-buoy sync engine.
 *
 * Consumers provide drivers by adding `@Provides @IntoSet` bindings in their
 * own Hilt modules:
 *
 * ```kotlin
 * @Module
 * @InstallIn(SingletonComponent::class)
 * object SyncModule {
 *
 *     @Provides @IntoSet
 *     fun commentService(apiClient: ApiClient): SyncDriver<*, *> =
 *         CommentService(apiClient).syncDriver
 *
 *     @Provides @IntoSet
 *     fun postService(apiClient: ApiClient): SyncDriver<*, *> =
 *         PostService(apiClient).syncDriver
 * }
 * ```
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
public interface DataBuoyEntryPoint {
    public fun syncDrivers(): Set<@JvmSuppressWildcards SyncDriver<*, *>>
}

/**
 * Provides a default empty set binding for [SyncDriver] so that apps
 * including the `:hilt` module don't crash at runtime if no drivers have been
 * bound via `@IntoSet` yet. Without this, Dagger would throw a missing binding
 * error when [DataBuoyEntryPoint.syncDrivers] is accessed.
 */
@Module
@InstallIn(SingletonComponent::class)
public abstract class DataBuoyMultibindsModule {
    @Multibinds
    public abstract fun syncDrivers(): Set<@JvmSuppressWildcards SyncDriver<*, *>>
}
