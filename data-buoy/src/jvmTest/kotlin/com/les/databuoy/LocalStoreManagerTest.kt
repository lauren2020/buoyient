package com.les.databuoy

import com.les.databuoy.testing.TestDatabaseFactory
import com.les.databuoy.db.SyncDatabase
import com.les.databuoy.internalutilities.LocalStoreManager
import com.les.databuoy.internalutilities.PendingRequestQueueManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalStoreManagerTest {

    // region helpers

    private enum class TestRequestTag(override val value: String) : ServiceRequestTag {
        DEFAULT("default"),
        ALTERNATE("alternate"),
    }

    private val codec = SyncCodec(TestItem.serializer())

    private fun testItem(
        clientId: String = "client-1",
        serverId: String? = null,
        version: Int = 1,
        name: String = "Test",
        value: Int = 0,
        syncStatus: SyncableObject.SyncStatus = SyncableObject.SyncStatus.LocalOnly,
    ) = TestItem(serverId, clientId, version, syncStatus, name, value)

    private fun makeRequest(
        method: HttpRequest.HttpMethod = HttpRequest.HttpMethod.POST,
        endpoint: String = "https://api.test.com/items",
        body: JsonObject = JsonObject(emptyMap()),
    ) = HttpRequest(method = method, endpointUrl = endpoint, requestBody = body)

    private fun createManager(
        database: SyncDatabase = TestDatabaseFactory.createInMemory(),
        serviceName: String = "test-service",
        syncScheduleNotifier: SyncScheduleNotifier = object : SyncScheduleNotifier {
            override fun scheduleSyncIfNeeded() {}
        },
    ): LocalStoreManager<TestItem, TestRequestTag> {
        return LocalStoreManager(
            database = database,
            serviceName = serviceName,
            syncScheduleNotifier = syncScheduleNotifier,
            codec = codec,
        )
    }

    // endregion

    // region insertLocalData

    @Test
    fun `insertLocalData stores data and queues a pending CREATE request`() {
        val manager = createManager()
        val item = testItem()

        val (returnedItem, result) = manager.insertLocalData(
            data = item, httpRequest = makeRequest(),
            idempotencyKey = "key-1", requestTag = TestRequestTag.DEFAULT,
        )

        assertIs<PendingRequestQueueManager.QueueResult.Stored>(result)
        assertEquals(item.clientId, returnedItem.clientId)
        assertIs<SyncableObject.SyncStatus.PendingCreate>(returnedItem.syncStatus)
    }

    @Test
    fun `insertLocalData makes data retrievable via getData`() {
        val db = TestDatabaseFactory.createInMemory()
        val manager = createManager(database = db)

        manager.insertLocalData(
            data = testItem(clientId = "c-1", name = "Widget"), httpRequest = makeRequest(),
            idempotencyKey = "key-1", requestTag = TestRequestTag.DEFAULT,
        )

        val entry = manager.getData(clientId = "c-1", serverId = null)
        assertNotNull(entry)
        assertEquals("Widget", entry.data.name)
        assertIs<SyncableObject.SyncStatus.PendingCreate>(entry.syncStatus)
    }

    @Test
    fun `insertLocalData reports hasPendingRequests true`() {
        val db = TestDatabaseFactory.createInMemory()
        val manager = createManager(database = db)
        val item = testItem()

        manager.insertLocalData(
            data = item, httpRequest = makeRequest(),
            idempotencyKey = "key-1", requestTag = TestRequestTag.DEFAULT,
        )

        assertTrue(manager.hasPendingRequests(item.clientId))
    }

    @Test
    fun `insertLocalData notifies sync scheduler`() {
        val notifier = object : SyncScheduleNotifier {
            var count = 0
            override fun scheduleSyncIfNeeded() { count++ }
        }
        val manager = createManager(syncScheduleNotifier = notifier)

        manager.insertLocalData(
            data = testItem(), httpRequest = makeRequest(),
            idempotencyKey = "key-1", requestTag = TestRequestTag.DEFAULT,
        )

        assertEquals(1, notifier.count)
    }

    @Test
    fun `insertLocalData with serverAttemptMade flag passes through to queue`() {
        val db = TestDatabaseFactory.createInMemory()
        val manager = createManager(database = db)
        val item = testItem()

        manager.insertLocalData(
            data = item, httpRequest = makeRequest(),
            idempotencyKey = "key-1", requestTag = TestRequestTag.DEFAULT,
            serverAttemptMade = true,
        )

        val pending = manager.pendingRequestQueueManager.getPendingRequests(item.clientId)
        assertEquals(1, pending.size)
        assertTrue(pending[0].serverAttemptMade)
    }

    // endregion

    // region insertFromServerResponse

    @Test
    fun `insertFromServerResponse stores synced data with server info`() {
        val db = TestDatabaseFactory.createInMemory()
        val manager = createManager(database = db)
        val item = testItem(clientId = "c-1", serverId = "s-1", version = 2, name = "Synced")

        manager.insertFromServerResponse(serverData = item, responseTimestamp = "2024-01-01T00:00:00Z")

        val entry = manager.getData(clientId = "c-1", serverId = "s-1")
        assertNotNull(entry)
        assertEquals("s-1", entry.data.serverId)
        assertEquals("Synced", entry.data.name)
        assertIs<SyncableObject.SyncStatus.Synced>(entry.syncStatus)
        assertEquals("2024-01-01T00:00:00Z", entry.lastSyncedTimestamp)
    }

    @Test
    fun `insertFromServerResponse stores last synced server data`() {
        val db = TestDatabaseFactory.createInMemory()
        val manager = createManager(database = db)
        val item = testItem(clientId = "c-1", serverId = "s-1", version = 1, name = "FromServer")

        manager.insertFromServerResponse(serverData = item, responseTimestamp = "2024-01-01T00:00:00Z")

        val entry = manager.getData(clientId = "c-1", serverId = "s-1")
        assertNotNull(entry)
        assertNotNull(entry.latestServerData)
        assertEquals("FromServer", entry.latestServerData!!.name)
    }

    @Test
    fun `insertFromServerResponse does not queue pending requests`() {
        val db = TestDatabaseFactory.createInMemory()
        val manager = createManager(database = db)

        manager.insertFromServerResponse(
            serverData = testItem(clientId = "c-1", serverId = "s-1"),
            responseTimestamp = "2024-01-01T00:00:00Z",
        )

        assertFalse(manager.hasPendingRequests("c-1"))
    }

    // endregion

    // region updateLocalData

    @Test
    fun `updateLocalData updates data and queues pending UPDATE request`() {
        val db = TestDatabaseFactory.createInMemory()
        val manager = createManager(database = db)
        val original = testItem(clientId = "c-1", serverId = "s-1", version = 1, name = "Original")

        manager.insertFromServerResponse(serverData = original, responseTimestamp = "2024-01-01T00:00:00Z")

        val updated = original.copy(version = 2, name = "Updated")
        val (returnedItem, result) = manager.updateLocalData(
            data = updated, idempotencyKey = "key-2",
            updateRequest = makeRequest(method = HttpRequest.HttpMethod.PUT),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Queue.Preferred(
                baseData = original, hasPendingRequests = false,
            ),
            requestTag = TestRequestTag.DEFAULT,
        )

        assertIs<PendingRequestQueueManager.QueueResult.Stored>(result)
        assertEquals("Updated", returnedItem.name)
        assertTrue(manager.hasPendingRequests("c-1"))
    }

    @Test
    fun `updateLocalData persists PENDING_UPDATE status in sync data`() {
        val db = TestDatabaseFactory.createInMemory()
        val manager = createManager(database = db)
        val original = testItem(clientId = "c-1", serverId = "s-1", version = 1, name = "Original")

        manager.insertFromServerResponse(serverData = original, responseTimestamp = "2024-01-01T00:00:00Z")

        manager.updateLocalData(
            data = original.copy(version = 2, name = "Updated"),
            idempotencyKey = "key-2",
            updateRequest = makeRequest(method = HttpRequest.HttpMethod.PUT),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Queue.Preferred(
                baseData = original, hasPendingRequests = false,
            ),
            requestTag = TestRequestTag.DEFAULT,
        )

        val entry = manager.getData(clientId = "c-1", serverId = "s-1")
        assertNotNull(entry)
        assertIs<SyncableObject.SyncStatus.PendingUpdate>(entry.syncStatus)
        assertEquals("Updated", entry.data.name)
    }

    @Test
    fun `updateLocalData rolls back sync data when queueing is invalid`() {
        val db = TestDatabaseFactory.createInMemory()
        val manager = createManager(database = db)
        val original = testItem(clientId = "c-1", serverId = "s-1", version = 1, name = "Original")

        manager.insertFromServerResponse(serverData = original, responseTimestamp = "2024-01-01T00:00:00Z")
        manager.voidData(
            data = original,
            httpRequest = makeRequest(method = HttpRequest.HttpMethod.DELETE),
            idempotencyKey = "void-key-1",
            requestTag = TestRequestTag.DEFAULT,
        )

        val updateResult = manager.updateLocalData(
            data = original.copy(version = 2, name = "Should Not Persist"),
            idempotencyKey = "key-2",
            updateRequest = makeRequest(method = HttpRequest.HttpMethod.PUT),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Queue.Preferred(
                baseData = original, hasPendingRequests = true,
            ),
            requestTag = TestRequestTag.DEFAULT,
        )

        assertIs<PendingRequestQueueManager.QueueResult.InvalidQueueRequest>(updateResult.second)

        val entry = manager.getData(clientId = "c-1", serverId = "s-1")
        assertNotNull(entry)
        assertIs<SyncableObject.SyncStatus.PendingVoid>(entry.syncStatus)
        assertEquals("Original", entry.data.name)
        assertEquals(1, entry.data.version)
    }

    @Test
    fun `updateLocalData notifies sync scheduler`() {
        val notifier = object : SyncScheduleNotifier {
            var count = 0
            override fun scheduleSyncIfNeeded() { count++ }
        }
        val db = TestDatabaseFactory.createInMemory()
        val manager = createManager(database = db, syncScheduleNotifier = notifier)
        val original = testItem(clientId = "c-1", serverId = "s-1", version = 1)

        manager.insertFromServerResponse(serverData = original, responseTimestamp = "2024-01-01T00:00:00Z")

        manager.updateLocalData(
            data = original.copy(version = 2, name = "Updated"), idempotencyKey = "key-2",
            updateRequest = makeRequest(method = HttpRequest.HttpMethod.PUT),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Queue.Preferred(
                baseData = original, hasPendingRequests = false,
            ),
            requestTag = TestRequestTag.DEFAULT,
        )

        assertEquals(1, notifier.count)
    }

    // endregion

    // region upsertFromServerResponse

    @Test
    fun `upsertFromServerResponse upserts server data for existing entry`() {
        val db = TestDatabaseFactory.createInMemory()
        val manager = createManager(database = db)
        val original = testItem(clientId = "c-1", serverId = "s-1", version = 1, name = "V1")

        manager.insertFromServerResponse(serverData = original, responseTimestamp = "2024-01-01T00:00:00Z")

        val serverUpdate = original.copy(version = 2, name = "V2")
        manager.upsertFromServerResponse(serverData = serverUpdate, responseTimestamp = "2024-01-02T00:00:00Z")

        val entry = manager.getData(clientId = "c-1", serverId = "s-1")
        assertNotNull(entry)
        assertEquals("V2", entry.data.name)
        assertEquals(2, entry.data.version)
        assertIs<SyncableObject.SyncStatus.Synced>(entry.syncStatus)
    }

    // endregion

    // region voidData

    @Test
    fun `voidData marks data voided and queues pending VOID request`() {
        val db = TestDatabaseFactory.createInMemory()
        val manager = createManager(database = db)
        val item = testItem(clientId = "c-1", serverId = "s-1", version = 1)

        manager.insertFromServerResponse(serverData = item, responseTimestamp = "2024-01-01T00:00:00Z")

        val (_, result) = manager.voidData(
            data = item, httpRequest = makeRequest(method = HttpRequest.HttpMethod.DELETE),
            idempotencyKey = "void-key-1", requestTag = TestRequestTag.DEFAULT,
        )

        assertIs<PendingRequestQueueManager.QueueResult.Stored>(result)
        assertTrue(manager.hasPendingRequests("c-1"))
    }

    @Test
    fun `voidData persists PENDING_VOID status in sync data`() {
        val db = TestDatabaseFactory.createInMemory()
        val manager = createManager(database = db)
        val item = testItem(clientId = "c-1", serverId = "s-1", version = 1)

        manager.insertFromServerResponse(serverData = item, responseTimestamp = "2024-01-01T00:00:00Z")

        manager.voidData(
            data = item,
            httpRequest = makeRequest(method = HttpRequest.HttpMethod.DELETE),
            idempotencyKey = "void-key-1",
            requestTag = TestRequestTag.DEFAULT,
        )

        val entry = manager.getData(clientId = "c-1", serverId = "s-1")
        assertNotNull(entry)
        assertIs<SyncableObject.SyncStatus.PendingVoid>(entry.syncStatus)
    }

    @Test
    fun `voidData with serverAttemptMade passes through to queue`() {
        val db = TestDatabaseFactory.createInMemory()
        val manager = createManager(database = db)
        val item = testItem(clientId = "c-1", serverId = "s-1", version = 1)

        manager.insertFromServerResponse(serverData = item, responseTimestamp = "2024-01-01T00:00:00Z")

        manager.voidData(
            data = item, httpRequest = makeRequest(method = HttpRequest.HttpMethod.DELETE),
            idempotencyKey = "void-key-2", requestTag = TestRequestTag.DEFAULT,
            serverAttemptMade = true,
        )

        val pending = manager.pendingRequestQueueManager.getPendingRequests(item.clientId)
        assertEquals(1, pending.size)
        assertTrue(pending[0].serverAttemptMade)
    }

    // endregion

    // region voidLocalOnlyData

    @Test
    fun `voidLocalOnlyData voids a local-only item and clears pending requests`() {
        val db = TestDatabaseFactory.createInMemory()
        val manager = createManager(database = db)
        val item = testItem(clientId = "c-1")

        manager.insertLocalData(
            data = item, httpRequest = makeRequest(),
            idempotencyKey = "key-1", requestTag = TestRequestTag.DEFAULT,
        )
        assertTrue(manager.hasPendingRequests("c-1"))

        val result = manager.voidLocalOnlyData(item)

        assertIs<SyncableObject.SyncStatus.LocalOnly>(result.syncStatus)
        assertFalse(manager.hasPendingRequests("c-1"))
    }

    // endregion

    // region getData

    @Test
    fun `getData returns null for missing entry`() {
        val manager = createManager()
        assertNull(manager.getData(clientId = "nonexistent", serverId = null))
    }

    @Test
    fun `getData returns correct SYNCED status with timestamp`() {
        val db = TestDatabaseFactory.createInMemory()
        val manager = createManager(database = db)

        manager.insertFromServerResponse(
            serverData = testItem(clientId = "c-1", serverId = "s-1"),
            responseTimestamp = "2024-06-15T12:00:00Z",
        )

        val entry = manager.getData(clientId = "c-1", serverId = "s-1")
        assertNotNull(entry)
        assertIs<SyncableObject.SyncStatus.Synced>(entry.syncStatus)
        assertEquals("2024-06-15T12:00:00Z",
            (entry.syncStatus as SyncableObject.SyncStatus.Synced).lastSyncedTimestamp)
    }

    // endregion

    // region getAllData

    @Test
    fun `getAllData retrieves all entries`() {
        val db = TestDatabaseFactory.createInMemory()
        val manager = createManager(database = db)

        manager.insertFromServerResponse(
            serverData = testItem(clientId = "c-1", serverId = "s-1", name = "First"),
            responseTimestamp = "2024-01-01T00:00:00Z",
        )
        manager.insertFromServerResponse(
            serverData = testItem(clientId = "c-2", serverId = "s-2", name = "Second"),
            responseTimestamp = "2024-01-02T00:00:00Z",
        )

        assertEquals(2, manager.getAllData(limit = 100).size)
    }

    @Test
    fun `getAllData respects limit parameter`() {
        val db = TestDatabaseFactory.createInMemory()
        val manager = createManager(database = db)

        for (i in 1..5) {
            manager.insertFromServerResponse(
                serverData = testItem(clientId = "c-$i", serverId = "s-$i"),
                responseTimestamp = "2024-01-0${i}T00:00:00Z",
            )
        }

        assertEquals(3, manager.getAllData(limit = 3).size)
    }

    @Test
    fun `getAllData returns empty list when no data exists`() {
        assertEquals(0, createManager().getAllData(limit = 100).size)
    }

    @Test
    fun `getAllData isolates by service name`() {
        val db = TestDatabaseFactory.createInMemory()
        val managerA = createManager(database = db, serviceName = "service-a")
        val managerB = createManager(database = db, serviceName = "service-b")

        managerA.insertFromServerResponse(
            serverData = testItem(clientId = "c-1", serverId = "s-1", name = "A"),
            responseTimestamp = "2024-01-01T00:00:00Z",
        )
        managerB.insertFromServerResponse(
            serverData = testItem(clientId = "c-2", serverId = "s-2", name = "B"),
            responseTimestamp = "2024-01-01T00:00:00Z",
        )

        assertEquals(1, managerA.getAllData(limit = 100).size)
        assertEquals("A", managerA.getAllData(limit = 100)[0].data.name)
        assertEquals(1, managerB.getAllData(limit = 100).size)
        assertEquals("B", managerB.getAllData(limit = 100)[0].data.name)
    }

    // endregion

    // region upsertEntry

    @Test
    fun `upsertEntry inserts new entry with SYNCED status`() {
        val db = TestDatabaseFactory.createInMemory()
        val manager = createManager(database = db)

        manager.upsertEntry(
            serverObj = testItem(clientId = "c-1", serverId = "s-1", version = 5, name = "Upserted"),
            syncedAtTimestamp = "2024-03-01T00:00:00Z",
            clientId = "c-1",
        )

        val entry = manager.getData(clientId = "c-1", serverId = "s-1")
        assertNotNull(entry)
        assertEquals("Upserted", entry.data.name)
        assertIs<SyncableObject.SyncStatus.Synced>(entry.syncStatus)
    }

    @Test
    fun `upsertEntry overwrites existing entry`() {
        val db = TestDatabaseFactory.createInMemory()
        val manager = createManager(database = db)

        manager.insertFromServerResponse(
            serverData = testItem(clientId = "c-1", serverId = "s-1", version = 1, name = "Original"),
            responseTimestamp = "2024-01-01T00:00:00Z",
        )

        manager.upsertEntry(
            serverObj = testItem(clientId = "c-1", serverId = "s-1", version = 2, name = "Overwritten"),
            syncedAtTimestamp = "2024-02-01T00:00:00Z",
            clientId = "c-1",
        )

        val entry = manager.getData(clientId = "c-1", serverId = "s-1")
        assertNotNull(entry)
        assertEquals("Overwritten", entry.data.name)
        assertEquals(2, entry.data.version)
    }

    // endregion

    // region getEffectiveBaseDataForUpdate

    @Test
    fun `getEffectiveBaseDataForUpdate returns latestServerData for synced entry`() {
        val db = TestDatabaseFactory.createInMemory()
        val manager = createManager(database = db)
        val item = testItem(clientId = "c-1", serverId = "s-1", version = 1, name = "ServerV1")

        manager.insertFromServerResponse(serverData = item, responseTimestamp = "2024-01-01T00:00:00Z")

        val base = manager.getEffectiveBaseDataForUpdate(item, PendingRequestQueueStrategy.Queue)
        assertEquals("ServerV1", base.name)
        assertEquals(1, base.version)
    }

    @Test
    fun `getEffectiveBaseDataForUpdate returns latest pending request data for PendingCreate`() {
        val db = TestDatabaseFactory.createInMemory()
        val manager = createManager(database = db)
        val item = testItem(clientId = "c-1", name = "Created")

        manager.insertLocalData(
            data = item, httpRequest = makeRequest(),
            idempotencyKey = "key-1", requestTag = TestRequestTag.DEFAULT,
        )

        val base = manager.getEffectiveBaseDataForUpdate(item, PendingRequestQueueStrategy.Queue)
        assertEquals("Created", base.name)
    }

    @Test
    fun `getEffectiveBaseDataForUpdate returns latest pending request data for PendingUpdate`() {
        val db = TestDatabaseFactory.createInMemory()
        val manager = createManager(database = db)
        val original = testItem(clientId = "c-1", serverId = "s-1", version = 1, name = "Original")

        manager.insertFromServerResponse(serverData = original, responseTimestamp = "2024-01-01T00:00:00Z")

        val updated = original.copy(version = 2, name = "Updated")
        manager.updateLocalData(
            data = updated, idempotencyKey = "key-2",
            updateRequest = makeRequest(method = HttpRequest.HttpMethod.PUT),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Queue.Preferred(
                baseData = original, hasPendingRequests = false,
            ),
            requestTag = TestRequestTag.DEFAULT,
        )

        val base = manager.getEffectiveBaseDataForUpdate(updated, PendingRequestQueueStrategy.Queue)
        assertEquals("Updated", base.name)
    }

    @Test
    fun `getEffectiveBaseDataForUpdate throws for PendingVoid entry`() {
        val db = TestDatabaseFactory.createInMemory()
        val manager = createManager(database = db)
        val item = testItem(clientId = "c-1", serverId = "s-1", version = 1)

        manager.insertFromServerResponse(serverData = item, responseTimestamp = "2024-01-01T00:00:00Z")
        manager.voidData(
            data = item, httpRequest = makeRequest(method = HttpRequest.HttpMethod.DELETE),
            idempotencyKey = "void-key-1", requestTag = TestRequestTag.DEFAULT,
        )

        val exception = try {
            manager.getEffectiveBaseDataForUpdate(item, PendingRequestQueueStrategy.Queue)
            null
        } catch (e: Exception) { e }

        assertNotNull(exception)
        assertTrue(exception.message!!.contains("voided"))
    }

    @Test
    fun `getEffectiveBaseDataForUpdate throws for nonexistent entry`() {
        val manager = createManager()
        val item = testItem(clientId = "nonexistent", serverId = null)

        val exception = try {
            manager.getEffectiveBaseDataForUpdate(item, PendingRequestQueueStrategy.Queue)
            null
        } catch (e: Exception) { e }

        assertNotNull(exception)
        assertTrue(exception.message!!.contains("Failed to find"))
    }

    // endregion

    // region service isolation

    @Test
    fun `getData does not return data from a different service`() {
        val db = TestDatabaseFactory.createInMemory()
        val managerA = createManager(database = db, serviceName = "service-a")
        val managerB = createManager(database = db, serviceName = "service-b")

        managerA.insertFromServerResponse(
            serverData = testItem(clientId = "c-1", serverId = "s-1", name = "OnlyInA"),
            responseTimestamp = "2024-01-01T00:00:00Z",
        )

        assertNull(managerB.getData(clientId = "c-1", serverId = "s-1"))
    }

    // endregion
}
