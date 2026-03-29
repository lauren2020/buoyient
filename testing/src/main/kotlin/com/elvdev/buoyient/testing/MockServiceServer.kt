package com.elvdev.buoyient.testing

import kotlinx.serialization.json.JsonObject

/**
 * Abstract base class for defining a mock server for a single service.
 *
 * Subclass this to create a self-contained mock server that encapsulates seed data
 * and HTTP handler registration for one [com.elvdev.buoyient.SyncableObjectService].
 * Register instances with [MockModeBuilder.service] to wire them into mock mode.
 *
 * ## Example
 *
 * ```kotlin
 * class MockNoteServer : MockServiceServer() {
 *     override val name = "notes"
 *     override val seedFile = "seeds/notes.json"
 *
 *     override fun registerHandlers(
 *         router: MockEndpointRouter,
 *         collection: MockServerCollection,
 *     ) {
 *         router.registerCrudHandlers(
 *             collection = collection,
 *             baseUrl = "https://api.example.com/v1/notes",
 *         )
 *     }
 * }
 * ```
 *
 * @see MockModeBuilder
 */
public abstract class MockServiceServer {

    /**
     * Collection name — must match the real service's `serviceName`.
     */
    public abstract val name: String

    /**
     * Seed entries to pre-populate in the mock server collection.
     *
     * Override this to provide inline seed data. Mutually exclusive with [seedFile].
     */
    public open val seeds: List<SeedEntry> get() = emptyList()

    /**
     * Classpath resource path to a JSON file containing seed data
     * (e.g. `"seeds/notes.json"`).
     *
     * The file must contain a JSON array of domain objects. Server IDs are
     * auto-generated for each entry. Mutually exclusive with [seeds].
     */
    public open val seedFile: String? get() = null

    /**
     * Register all mock HTTP handlers for this service.
     *
     * Called during [MockModeBuilder.install] after seed data has been populated
     * into [collection]. Use the router's `onGet`, `onPost`, `onPut`, `onDelete`
     * methods or the [registerCrudHandlers] extension to wire up endpoints.
     *
     * @param router the mock HTTP router to register handlers on.
     * @param collection the pre-seeded server-side collection for this service.
     */
    public abstract fun registerHandlers(
        router: MockEndpointRouter,
        collection: MockServerCollection,
    )

    /**
     * A seed entry for pre-populating a mock server collection.
     *
     * @property data the domain fields for this record.
     * @property serverId optional explicit server ID (auto-generated if null).
     * @property clientId optional client ID for correlation.
     * @property version the version number (defaults to 1).
     */
    public data class SeedEntry(
        val data: JsonObject,
        val serverId: String? = null,
        val clientId: String? = null,
        val version: Int = 1,
    )
}
