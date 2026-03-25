package com.les.databuoy

import platform.Foundation.NSUUID

actual fun createPlatformIdGenerator(): IdGenerator = IosIdGenerator()

class IosIdGenerator : IdGenerator {
    override fun generateId(): String = NSUUID().UUIDString()
}
