package com.les.databuoy

/**
 * Platform-agnostic interface for generating unique identifiers.
 * On Android/JVM, this wraps java.util.UUID. On iOS, this wraps NSUUID.
 */
interface IdGenerator {
    fun generateId(): String
}

/**
 * Returns the platform-specific [IdGenerator] implementation.
 * On Android/JVM this uses `java.util.UUID`; on iOS `platform.Foundation.NSUUID`.
 */
expect fun createPlatformIdGenerator(): IdGenerator
