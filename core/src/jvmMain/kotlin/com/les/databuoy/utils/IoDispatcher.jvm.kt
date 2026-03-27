package com.les.databuoy.utils

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

public actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
