package com.les.databuoy.testing

import com.les.databuoy.IdGenerator
import com.les.databuoy.DataBuoyStatus
import com.les.databuoy.LocalStoreManager
import com.les.databuoy.ServerManager
import com.les.databuoy.ServiceRequestTag
import com.les.databuoy.SyncCodec
import com.les.databuoy.SyncLog
import com.les.databuoy.SyncLogger
import com.les.databuoy.SyncScheduleNotifier
import com.les.databuoy.SyncableObject
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
 * val service = MyItemService(
 *     serverProcessingConfig = myConfig,
 *     connectivityChecker = env.connectivityChecker,
 *     serverManager = env.serverManager,
 *     localStoreManager = env.createLocalStoreManager(codec, "my-items"),
 *     syncScheduleNotifier = env.syncScheduleNotifier,
 * )
 * ```
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
 *   gets its own isolated database.
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
    }

    /**
     * A [ServerManager] backed by [mockRouter]. Lazily created so that handlers
     * registered after construction are still picked up (handlers are evaluated
     * at request time, not at build time).
     */
    val serverManager: ServerManager by lazy {
        mockRouter.buildServerManager()
    }

    /**
     * Creates a [LocalStoreManager] wired to this environment's [database].
     *
     * @param codec the serialization codec for the syncable object type.
     * @param serviceName the service name used as a namespace in the database.
     */
    fun <O : SyncableObject<O>, T : ServiceRequestTag> createLocalStoreManager(
        codec: SyncCodec<O>,
        serviceName: String,
    ): LocalStoreManager<O, T> = LocalStoreManager(
        database = database,
        serviceName = serviceName,
        syncScheduleNotifier = syncScheduleNotifier,
        codec = codec,
        status = DataBuoyStatus(database),
    )
}
