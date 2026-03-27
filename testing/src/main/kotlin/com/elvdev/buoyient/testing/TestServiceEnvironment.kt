package com.elvdev.buoyient.testing

import com.elvdev.buoyient.globalconfigs.DatabaseOverride
import com.elvdev.buoyient.globalconfigs.HttpClientOverride
import com.elvdev.buoyient.utils.IdGenerator
import com.elvdev.buoyient.utils.BuoyientLog
import com.elvdev.buoyient.utils.BuoyientLogger
import com.elvdev.buoyient.sync.SyncScheduleNotifier
import com.elvdev.buoyient.db.SyncDatabase

/**
 * All-in-one test harness that bundles every dependency a [SyncableObjectService]
 * needs, pre-wired with test-friendly defaults.
 *
 * ## Usage
 *
 * ```kotlin
 * val env = TestServiceEnvironment()
 * env.mockRouter.onPost("https://api.example.com/items") { request ->
 *     MockResponse(statusCode = 201, body = buildJsonObject { ... })
 * }
 *
 * val service = MyItemService(connectivityChecker = env.connectivityChecker)
 * ```
 *
 * Creating a [TestServiceEnvironment] installs the mock HTTP client, in-memory
 * database, deterministic ID generator, and logger as process-wide overrides.
 * Any service constructed after this point automatically uses the test doubles —
 * no manual constructor injection needed.
 *
 * @property mockRouter the mock endpoint router — register handlers here before exercising the service.
 * @property connectivityChecker mutable connectivity state. Defaults to online.
 * @property logger the logger to install into [BuoyientLog] for the duration of this environment.
 *   Defaults to silent ([NoOpSyncLogger]). Pass [PrintSyncLogger] to see sync engine
 *   activity during debugging.
 * @property syncScheduleNotifier no-op notifier by default.
 * @property idGenerator deterministic ID generator. Also installed as the global
 *   [IdGenerator.generator] so that service code picks it up automatically.
 * @property database in-memory SQLite database. Each [TestServiceEnvironment] instance
 *   gets its own isolated database, installed as the global [DatabaseOverride].
 * @property mockServerStore optional stateful mock server store. Use
 *   [MockServerStore.collection] to get a [MockServerCollection] and wire it to
 *   [mockRouter] via [registerCrudHandlers] or [registerSyncDownHandler] for
 *   realistic server-side state. Defaults to a fresh store instance.
 */
public class TestServiceEnvironment(
    public val mockRouter: MockEndpointRouter = MockEndpointRouter(),
    public val connectivityChecker: TestConnectivityChecker = TestConnectivityChecker(online = true),
    public val logger: BuoyientLogger = NoOpSyncLogger,
    public val syncScheduleNotifier: SyncScheduleNotifier = NoOpSyncScheduleNotifier,
    public val idGenerator: IdGenerator = IncrementingIdGenerator(),
    public val database: SyncDatabase = TestDatabaseFactory.createInMemory(),
    public val mockServerStore: MockServerStore = MockServerStore(),
) {
    init {
        BuoyientLog.logger = logger
        IdGenerator.generator = idGenerator
        HttpClientOverride.httpClient = mockRouter.buildHttpClient()
        DatabaseOverride.database = database
    }
}
