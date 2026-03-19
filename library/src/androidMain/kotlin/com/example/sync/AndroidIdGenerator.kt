package com.example.sync

import java.util.UUID

actual fun createPlatformIdGenerator(): IdGenerator = AndroidIdGenerator()

class AndroidIdGenerator : IdGenerator {
    override fun generateId(): String = UUID.randomUUID().toString()
}
