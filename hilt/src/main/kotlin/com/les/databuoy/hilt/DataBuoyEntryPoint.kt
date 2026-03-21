package com.les.databuoy.hilt

import com.les.databuoy.SyncableObjectService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt entry point that exposes the app's [SyncableObjectService] set to the
 * data-buoy sync engine.
 *
 * Consumers provide services by adding `@Provides @IntoSet` bindings in their
 * own Hilt modules:
 *
 * ```kotlin
 * @Module
 * @InstallIn(SingletonComponent::class)
 * object SyncModule {
 *
 *     @Provides @IntoSet
 *     fun commentService(apiClient: ApiClient): SyncableObjectService<*> =
 *         CommentService(apiClient)
 *
 *     @Provides @IntoSet
 *     fun postService(apiClient: ApiClient): SyncableObjectService<*> =
 *         PostService(apiClient)
 * }
 * ```
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DataBuoyEntryPoint {
    fun syncServices(): Set<@JvmSuppressWildcards SyncableObjectService<*, *>>
}
