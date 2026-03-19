package com.example.sync

import platform.Foundation.NSUUID

actual fun createPlatformIdGenerator(): IdGenerator = IosIdGenerator()

class IosIdGenerator : IdGenerator {
    override fun generateId(): String = NSUUID().UUIDString()
}
