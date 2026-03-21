package com.example.sync.testing

import com.example.sync.IdGenerator
import java.util.concurrent.atomic.AtomicInteger

/**
 * An [IdGenerator] that produces deterministic, incrementing IDs.
 * This makes test assertions against idempotency keys predictable.
 *
 * Generated IDs follow the pattern `{prefix}-{counter}`, e.g. `test-id-1`, `test-id-2`.
 */
class IncrementingIdGenerator(
    private val prefix: String = "test-id",
) : IdGenerator {
    private val counter = AtomicInteger(0)

    override fun generateId(): String = "$prefix-${counter.incrementAndGet()}"

    /** Resets the counter back to 0. */
    fun reset() {
        counter.set(0)
    }
}
