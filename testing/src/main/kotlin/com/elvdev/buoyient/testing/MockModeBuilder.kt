package com.elvdev.buoyient.testing

import com.elvdev.buoyient.globalconfigs.Buoyient
import com.elvdev.buoyient.utils.BuoyientLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Builder for quickly setting up mock mode in a consuming app.
 *
 * Reduces mock mode setup to a few lines by creating the [MockEndpointRouter],
 * [MockServerStore], and wiring [registerCrudHandlers] for each service, then
 * installing the mock HTTP client globally via [Buoyient.httpClient].
 *
 * ## Quick start
 *
 * ```kotlin
 * val mockMode = MockModeBuilder()
 *     .service(
 *         name = "notes",
 *         baseUrl = "https://api.example.com/v1/notes",
 *         seeds = listOf(
 *             buildJsonObject { put("title", "Welcome"); put("body", "Hello") },
 *         ),
 *     )
 *     .service(
 *         name = "tasks",
 *         baseUrl = "https://api.example.com/v1/tasks",
 *     )
 *     .install()
 *
 * // Use mockMode.router, mockMode.store, mockMode.connectivityChecker for advanced use
 * ```
 *
 * For more control over response shapes, use [service] with custom [responseWrapper]
 * and [listResponseWrapper] parameters.
 */
public class MockModeBuilder {

    private data class ServiceConfig(
        val name: String,
        val baseUrl: String,
        val seeds: List<SeedEntry>,
        val seedFile: String?,
        val responseWrapper: ((MockServerRecord) -> JsonObject)?,
        val listResponseWrapper: ((List<MockServerRecord>) -> JsonObject)?,
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

    private val services = mutableListOf<ServiceConfig>()
    private var enableLogging = true

    /**
     * Registers a service for mock mode with automatic CRUD handlers.
     *
     * @param name the collection name (typically matches your service's `serviceName`).
     * @param baseUrl the base URL for the resource endpoints.
     * @param seeds optional list of records to pre-populate in the mock server.
     * @param responseWrapper optional custom single-record response wrapper.
     *   Defaults to `{"data": <record>}`.
     * @param listResponseWrapper optional custom list response wrapper.
     *   Defaults to `{"data": [<records>]}`.
     * @return this builder, for chaining.
     */
    public fun service(
        name: String,
        baseUrl: String,
        seeds: List<SeedEntry> = emptyList(),
        responseWrapper: ((MockServerRecord) -> JsonObject)? = null,
        listResponseWrapper: ((List<MockServerRecord>) -> JsonObject)? = null,
    ): MockModeBuilder {
        services.add(ServiceConfig(name, baseUrl, seeds, seedFile = null, responseWrapper, listResponseWrapper))
        return this
    }

    /**
     * Convenience overload that accepts raw [JsonObject] seeds without explicit IDs.
     *
     * @param name the collection name.
     * @param baseUrl the base URL for the resource endpoints.
     * @param seeds list of [JsonObject] data to pre-populate. Server IDs are auto-generated.
     * @param responseWrapper optional custom single-record response wrapper.
     * @param listResponseWrapper optional custom list response wrapper.
     * @return this builder, for chaining.
     */
    public fun service(
        name: String,
        baseUrl: String,
        seeds: List<JsonObject>,
        responseWrapper: ((MockServerRecord) -> JsonObject)? = null,
        listResponseWrapper: ((List<MockServerRecord>) -> JsonObject)? = null,
        @Suppress("UNUSED_PARAMETER") jsonSeeds: Unit = Unit, // disambiguation marker
    ): MockModeBuilder {
        services.add(
            ServiceConfig(
                name = name,
                baseUrl = baseUrl,
                seeds = seeds.map { SeedEntry(data = it) },
                seedFile = null,
                responseWrapper = responseWrapper,
                listResponseWrapper = listResponseWrapper,
            )
        )
        return this
    }

    /**
     * Registers a service for mock mode, loading seed data from a classpath JSON resource.
     *
     * The file must contain a JSON array of domain objects, e.g.:
     * ```json
     * [
     *   { "title": "Welcome", "body": "Hello" },
     *   { "name": "Minimal example", "amount": 42 }
     * ]
     * ```
     *
     * Server IDs are auto-generated for each entry (same behavior as passing `List<JsonObject>`
     * seeds directly).
     *
     * This is mutually exclusive with the in-memory seed overloads — use one or the other.
     *
     * @param name the collection name (typically matches your service's `serviceName`).
     * @param baseUrl the base URL for the resource endpoints.
     * @param seedFile classpath resource path to a JSON file (e.g. `"seeds/notes.json"`).
     * @param responseWrapper optional custom single-record response wrapper.
     * @param listResponseWrapper optional custom list response wrapper.
     * @return this builder, for chaining.
     */
    public fun service(
        name: String,
        baseUrl: String,
        seedFile: String,
        responseWrapper: ((MockServerRecord) -> JsonObject)? = null,
        listResponseWrapper: ((List<MockServerRecord>) -> JsonObject)? = null,
    ): MockModeBuilder {
        services.add(
            ServiceConfig(
                name = name,
                baseUrl = baseUrl,
                seeds = emptyList(),
                seedFile = seedFile,
                responseWrapper = responseWrapper,
                listResponseWrapper = listResponseWrapper,
            )
        )
        return this
    }

    /**
     * Whether to install [PrintSyncLogger] for verbose sync engine logging.
     * Defaults to `true`.
     */
    public fun logging(enabled: Boolean): MockModeBuilder {
        enableLogging = enabled
        return this
    }

    /**
     * Builds the mock mode infrastructure and installs it globally.
     *
     * Sets [Buoyient.httpClient] and optionally [BuoyientLog.logger]. After this call,
     * any [com.elvdev.buoyient.SyncableObjectService] constructed will route requests
     * through the mock handlers.
     *
     * @return a [MockModeHandle] with references to the router, store, and connectivity
     *   checker for further customization.
     */
    public fun install(): MockModeHandle {
        val store = MockServerStore()
        val router = MockEndpointRouter()
        val connectivityChecker = TestConnectivityChecker(online = true)

        for (config in services) {
            val collection = store.collection(config.name)

            // Resolve seeds: either from in-memory list or classpath file
            val seeds = if (config.seedFile != null) {
                val resourceUrl = Thread.currentThread().contextClassLoader
                    ?.getResource(config.seedFile)
                    ?: error(
                        "Seed file '${config.seedFile}' not found on the classpath. " +
                            "Place it in src/main/resources/ or src/debug/resources/."
                    )
                val json = resourceUrl.readText()
                val parsed = Json.decodeFromString<List<JsonObject>>(json)
                parsed.map { SeedEntry(data = it) }
            } else {
                config.seeds
            }

            // Seed data
            for (seed in seeds) {
                if (seed.serverId != null) {
                    collection.seed(
                        serverId = seed.serverId,
                        data = seed.data,
                        version = seed.version,
                        clientId = seed.clientId,
                    )
                } else {
                    // Use create() which auto-generates a server ID
                    collection.create(seed.data)
                }
            }

            // Register CRUD handlers
            val singleWrapper = config.responseWrapper ?: ::defaultSingleWrapper
            val listWrapper = config.listResponseWrapper ?: ::defaultListWrapper

            router.registerCrudHandlers(
                collection = collection,
                baseUrl = config.baseUrl,
                responseWrapper = singleWrapper,
                listResponseWrapper = listWrapper,
            )
        }

        // Install globally
        Buoyient.httpClient = router.buildHttpClient()
        if (enableLogging) {
            BuoyientLog.logger = PrintSyncLogger
        }

        return MockModeHandle(
            router = router,
            store = store,
            connectivityChecker = connectivityChecker,
        )
    }
}

/**
 * Handle returned by [MockModeBuilder.install] with references to the mock
 * infrastructure for advanced use cases (custom handlers, offline simulation,
 * request inspection, etc.).
 */
public data class MockModeHandle(
    /** The mock HTTP router. Add custom handlers or inspect [MockEndpointRouter.requestLog]. */
    public val router: MockEndpointRouter,

    /** The stateful mock server store. Access collections for seeding, mutation, or inspection. */
    public val store: MockServerStore,

    /** Connectivity checker for simulating offline mode. Set [TestConnectivityChecker.online] to `false`. */
    public val connectivityChecker: TestConnectivityChecker,
)

// -- Default response wrappers (match MockServerStoreRouter defaults) --

private fun defaultSingleWrapper(record: MockServerRecord): JsonObject = buildJsonObject {
    put("data", record.toJsonObject())
}

private fun defaultListWrapper(records: List<MockServerRecord>): JsonObject = buildJsonObject {
    put("data", JsonArray(records.map { it.toJsonObject() }))
}
