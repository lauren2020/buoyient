package com.les.buoyient.utils

import kotlin.concurrent.Volatile

/**
 * Platform-agnostic interface for generating unique identifiers.
 * On Android/JVM, this wraps java.util.UUID. On iOS, this wraps NSUUID.
 */
public interface IdGenerator {
    public fun generateId(): String

    /**
     * Process-wide ID generator for the buoyient sync engine.
     *
     * By default this delegates to [createPlatformIdGenerator]. Swap
     * [generator] at startup to install a test or mock implementation —
     * for example, `IdGenerator.generator = IncrementingIdGenerator()`.
     */
    public companion object : IdGenerator {
        @Volatile
        public var generator: IdGenerator = createPlatformIdGenerator()

        override fun generateId(): String = generator.generateId()
    }
}

/**
 * Returns the platform-specific [IdGenerator] implementation.
 * On Android/JVM this uses `java.util.UUID`; on iOS `platform.Foundation.NSUUID`.
 */
public expect fun createPlatformIdGenerator(): IdGenerator
