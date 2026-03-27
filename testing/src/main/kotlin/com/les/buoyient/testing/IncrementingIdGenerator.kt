package com.les.buoyient.testing

import com.les.buoyient.utils.IdGenerator
import java.util.concurrent.atomic.AtomicInteger

/**
 * An [IdGenerator] that produces deterministic, incrementing IDs.
 * This makes test assertions against idempotency keys predictable.
 *
 * Generated IDs follow the pattern `{prefix}-{counter}`, e.g. `test-id-1`, `test-id-2`.
 */
public class IncrementingIdGenerator(
    private val prefix: String = "test-id",
) : IdGenerator {
    private val counter = AtomicInteger(0)

    override fun generateId(): String = "$prefix-${counter.incrementAndGet()}"

    /** Resets the counter back to 0. */
    public fun reset() {
        counter.set(0)
    }
}
