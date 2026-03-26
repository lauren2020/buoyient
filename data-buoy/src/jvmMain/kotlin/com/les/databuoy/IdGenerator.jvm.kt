package com.les.databuoy

import java.util.UUID

public actual fun createPlatformIdGenerator(): IdGenerator = object : IdGenerator {
    override fun generateId(): String = UUID.randomUUID().toString()
}
