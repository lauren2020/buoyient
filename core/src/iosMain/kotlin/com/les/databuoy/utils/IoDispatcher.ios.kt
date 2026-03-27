package com.les.databuoy.utils

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// Dispatchers.IO on Native is backed by Dispatchers.Default in kotlinx-coroutines 1.8.x.
// We reference Default directly here because the internal IO member shadows the public
// extension property in Kotlin 1.9.x, making Dispatchers.IO inaccessible.
public actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
