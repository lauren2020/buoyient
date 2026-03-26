package com.les.databuoy

import java.util.UUID

public actual fun createPlatformIdGenerator(): IdGenerator = AndroidIdGenerator()

public class AndroidIdGenerator : IdGenerator {
    override fun generateId(): String = UUID.randomUUID().toString()
}
