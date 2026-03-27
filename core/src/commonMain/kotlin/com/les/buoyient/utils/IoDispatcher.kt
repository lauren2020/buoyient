package com.les.buoyient.utils

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Platform-resolved IO dispatcher.
 *
 * On JVM/Android this returns [kotlinx.coroutines.Dispatchers.IO].
 * On Native this returns [kotlinx.coroutines.Dispatchers.Default] because
 * `Dispatchers.IO` is not publicly accessible on Native in kotlinx-coroutines 1.8.x
 * with Kotlin 1.9.x (the internal member shadows the public extension property).
 *
 * Remove this once the project upgrades to Kotlin 2.0+ / coroutines 1.9+.
 */
public expect val ioDispatcher: CoroutineDispatcher
