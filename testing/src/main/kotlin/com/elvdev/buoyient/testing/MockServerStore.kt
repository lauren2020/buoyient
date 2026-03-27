package com.elvdev.buoyient.testing

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A stateful mock server data store for testing buoyient services.
 *
 * Unlike the canned-response approach (registering static [MockResponse] handlers on
 * [MockEndpointRouter]), this store holds **persistent server-side state** that mock
 * HTTP handlers read from and write to. This enables realistic testing scenarios:
 *
 * - **Round-trip CRUD**: POST creates a real record; subsequent GETs return it.
 * - **Divergence simulation**: call [MockServerCollection.mutate] to change a record
 *   as if another client updated the server, then trigger sync-down.
 * - **Conflict testing**: modify the same record in both the client DB and this store,
 *   then sync and assert on the 3-way merge outcome.
 * - **Delta sync-down**: use [MockServerCollection.getUpdatedSince] to return only
 *   records newer than the client's last sync timestamp.
 *
 * ## Usage
 *
 * ```kotlin
 * val store = MockServerStore()
 * val todos = store.collection("todos")
 *
 * // Seed initial server state
 * todos.seed("srv-1", buildJsonObject { put("title", "Buy milk") })
 *
 * // Wire to MockEndpointRouter for automatic CRUD handling
 * router.registerCrudHandlers(todos, "https://api.example.com/todos")
 *
 * // Later, simulate another client's edit
 * todos.mutate("srv-1") { data ->
 *     buildJsonObject {
 *         data.forEach { (k, v) -> put(k, v) }
 *         put("title", "Buy oat milk")
 *     }
 * }
 * ```
 *
 * The store is fully optional — existing tests using canned `onGet`/`onPost` handlers
 * continue to work without any changes.
 *
 * @param idPrefix prefix for generated server IDs (e.g. "server" produces "server-1", "server-2").
 * @param clock provides the current time in epoch seconds. Override for deterministic tests.
 */
public class MockServerStore(
    private val idPrefix: String = "server",
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val collections = ConcurrentHashMap<String, MockServerCollection>()
    private val idCounter = AtomicInteger(0)

    /**
     * Returns the [MockServerCollection] with the given [name], creating it if it
     * doesn't exist yet. Collections are lazy — call this freely without worrying
     * about initialization order.
     */
    public fun collection(name: String): MockServerCollection =
        collections.getOrPut(name) {
            MockServerCollection(
                name = name,
                records = ConcurrentHashMap(),
                generateId = { "$idPrefix-${idCounter.incrementAndGet()}" },
                clock = clock,
            )
        }

    /**
     * Clears all collections and resets the ID counter. Call this between test
     * cases if reusing a single store instance.
     */
    public fun reset() {
        collections.values.forEach { it.clear() }
        collections.clear()
        idCounter.set(0)
    }
}
