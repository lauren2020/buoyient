package com.elvdev.buoyient.utils

import platform.Foundation.NSUUID

public actual fun createPlatformIdGenerator(): IdGenerator = IosIdGenerator()

public class IosIdGenerator : IdGenerator {
    override fun generateId(): String = NSUUID().UUIDString()
}
