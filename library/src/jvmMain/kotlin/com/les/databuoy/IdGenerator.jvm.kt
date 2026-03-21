package com.les.databuoy

import java.util.UUID

actual fun createPlatformIdGenerator(): IdGenerator = object : IdGenerator {
    override fun generateId(): String = UUID.randomUUID().toString()
}
