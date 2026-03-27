package com.elvdev.buoyient.utils
import java.util.UUID

public actual fun createPlatformIdGenerator(): IdGenerator = AndroidIdGenerator()

public class AndroidIdGenerator : IdGenerator {
    override fun generateId(): String = UUID.randomUUID().toString()
}
