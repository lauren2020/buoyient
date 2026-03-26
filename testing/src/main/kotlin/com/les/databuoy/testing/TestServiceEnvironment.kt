package com.les.databuoy.testing

import com.les.databuoy.DatabaseOverride
import com.les.databuoy.HttpClientOverride
import com.les.databuoy.IdGenerator
import com.les.databuoy.SyncLog
import com.les.databuoy.SyncLogger
import com.les.databuoy.SyncScheduleNotifier
import com.les.databuoy.db.SyncDatabase

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
 * @property logger the logger to install into [SyncLog] for the duration of this environment.
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
class TestServiceEnvironment(
    val mockRouter: MockEndpointRouter = MockEndpointRouter(),
    val connectivityChecker: TestConnectivityChecker = TestConnectivityChecker(online = true),
    val logger: SyncLogger = NoOpSyncLogger,
    val syncScheduleNotifier: SyncScheduleNotifier = NoOpSyncScheduleNotifier,
    val idGenerator: IdGenerator = IncrementingIdGenerator(),
    val database: SyncDatabase = TestDatabaseFactory.createInMemory(),
    val mockServerStore: MockServerStore = MockServerStore(),
) {
    init {
        SyncLog.logger = logger
        IdGenerator.generator = idGenerator
        HttpClientOverride.httpClient = mockRouter.buildHttpClient()
        DatabaseOverride.database = database
    }
}
