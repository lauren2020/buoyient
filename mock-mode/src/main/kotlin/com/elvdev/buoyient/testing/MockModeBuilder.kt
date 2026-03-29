package com.elvdev.buoyient.testing

import com.elvdev.buoyient.globalconfigs.Buoyient
import com.elvdev.buoyient.utils.BuoyientLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Builder for quickly setting up mock mode in a consuming app.
 *
 * Reduces mock mode setup to a few lines by creating the [MockEndpointRouter],
 * [MockServerStore], and wiring handlers for each service, then installing the
 * mock HTTP client globally via [Buoyient.httpClient].
 *
 * ## Quick start
 *
 * ```kotlin
 * val mockMode = MockModeBuilder()
 *     .service(MockNoteServer())
 *     .service(MockTaskServer())
 *     .install()
 *
 * // Use mockMode.router, mockMode.store, mockMode.connectivityChecker for advanced use
 * ```
 *
 * Each service is a [MockServiceServer] subclass that encapsulates its seed data
 * and handler registration.
 *
 * @see MockServiceServer
 */
public class MockModeBuilder {

    private val servers = mutableListOf<MockServiceServer>()
    private var enableLogging = true

    /**
     * Registers a [MockServiceServer] for mock mode.
     *
     * @param server the mock service server defining seed data and handlers.
     * @return this builder, for chaining.
     */
    public fun service(server: MockServiceServer): MockModeBuilder {
        servers.add(server)
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

        val endpointIndex = mutableMapOf<String, List<MockEndpoint>>()

        for (server in servers) {
            val collection = store.collection(server.name)

            // Resolve seeds: either from in-memory list or classpath file
            val seeds = if (server.seedFile != null) {
                val resourceUrl = Thread.currentThread().contextClassLoader
                    ?.getResource(server.seedFile!!)
                    ?: error(
                        "Seed file '${server.seedFile}' not found on the classpath. " +
                            "Place it in src/main/resources/ or src/debug/resources/."
                    )
                val json = resourceUrl.readText()
                val parsed = Json.decodeFromString<List<JsonObject>>(json)
                parsed.map { MockServiceServer.SeedEntry(data = it) }
            } else {
                server.seeds
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

            // Declare endpoints and register them on the router
            val endpoints = server.endpoints(collection)
            endpointIndex[server.name] = endpoints
            for (endpoint in endpoints) {
                router.on(endpoint.method, endpoint.urlPattern, endpoint.handler)
            }
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
            endpointIndex = endpointIndex,
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

    /**
     * Index of all declared endpoints, keyed by service name.
     *
     * Each entry maps a service's [MockServiceServer.name] to the list of
     * [MockEndpoint]s returned by [MockServiceServer.endpoints]. Use this to
     * enumerate endpoints for a global test controller (e.g. toggling specific
     * endpoints to return errors or timeouts).
     */
    public val endpointIndex: Map<String, List<MockEndpoint>>,
)
