package com.les.databuoy

import com.les.databuoy.db.SyncDatabase
import com.les.databuoy.internalutilities.LocalStoreManager
import com.les.databuoy.internalutilities.PendingRequestQueueManager
import com.les.databuoy.publicconfigs.PendingRequestQueueStrategy
import com.les.databuoy.publicconfigs.SyncableObjectRebaseHandler
import com.les.databuoy.testing.TestDatabaseFactory
import com.les.databuoy.utils.SyncCodec
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

class PendingRequestQueueManagerTest {

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
    ) = HttpRequest(
        method = method,
        endpointUrl = endpoint,
        requestBody = body,
    )

    private fun makeRequestWithBody(vararg pairs: Pair<String, String>): HttpRequest {
        val body = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }
        return makeRequest(body = body)
    }

    /** Identity squash merger — just returns the update request unchanged. */
    private val identitySquashMerger = SquashRequestMerger { _, updateRequest -> updateRequest }

    /** Squash merger that merges the body fields from both requests. */
    private val mergingSquashMerger = SquashRequestMerger { createRequest, updateRequest ->
        val mergedBody = buildJsonObject {
            createRequest.requestBody.forEach { (k, v) -> put(k, v.toString()) }
            updateRequest.requestBody.forEach { (k, v) -> put(k, v.toString()) }
        }
        HttpRequest(
            method = createRequest.method,
            endpointUrl = createRequest.endpointUrl,
            requestBody = mergedBody,
        )
    }

    private fun createManager(
        database: SyncDatabase = TestDatabaseFactory.createInMemory(),
        serviceName: String = "test-service",
        strategy: PendingRequestQueueStrategy =
            PendingRequestQueueStrategy.Queue,
        status: DataBuoyStatus = DataBuoyStatus(database),
    ) = PendingRequestQueueManager<TestItem, TestRequestTag>(
        database = database,
        serviceName = serviceName,
        strategy = strategy,
        codec = codec,
        status = status,
    )

    // endregion

    // region queueCreateRequest

    @Test
    fun `queueCreateRequest stores a create request`() {
        val manager = createManager()
        val item = testItem()
        val result = manager.queueCreateRequest(
            data = item,
            httpRequest = makeRequest(),
            idempotencyKey = "key-1",
            serverAttemptMade = false,
            requestTag = TestRequestTag.DEFAULT,
        )
        assertIs<PendingRequestQueueManager.QueueResult.Stored>(result)
        val pending = manager.getPendingRequests(item.clientId)
        assertEquals(1, pending.size)
        assertEquals(PendingSyncRequest.Type.CREATE, pending[0].type)
        assertEquals("key-1", pending[0].idempotencyKey)
        assertFalse(pending[0].serverAttemptMade)
    }

    @Test
    fun `queueCreateRequest rejects duplicate create for same clientId`() {
        val manager = createManager()
        val item = testItem()
        manager.queueCreateRequest(
            data = item, httpRequest = makeRequest(),
            idempotencyKey = "key-1", serverAttemptMade = false,
            requestTag = TestRequestTag.DEFAULT,
        )
        val result = manager.queueCreateRequest(
            data = item, httpRequest = makeRequest(),
            idempotencyKey = "key-2", serverAttemptMade = false,
            requestTag = TestRequestTag.DEFAULT,
        )
        assertIs<PendingRequestQueueManager.QueueResult.InvalidQueueRequest>(result)
        assertTrue(result.errorMessage.contains("already a create request"))
    }

    @Test
    fun `queueCreateRequest rejects create after void`() {
        val manager = createManager()
        val item = testItem()
        manager.queueVoidRequest(
            data = item, httpRequest = makeRequest(),
            idempotencyKey = "void-key", serverAttemptMade = false,
            lastSyncedServerData = null, requestTag = TestRequestTag.DEFAULT,
        )
        val result = manager.queueCreateRequest(
            data = item, httpRequest = makeRequest(),
            idempotencyKey = "create-key", serverAttemptMade = false,
            requestTag = TestRequestTag.DEFAULT,
        )
        assertIs<PendingRequestQueueManager.QueueResult.InvalidQueueRequest>(result)
        assertTrue(result.errorMessage.contains("voided"))
    }

    @Test
    fun `queueCreateRequest allows create for different clientIds`() {
        val manager = createManager()
        val result1 = manager.queueCreateRequest(
            data = testItem(clientId = "c1"), httpRequest = makeRequest(),
            idempotencyKey = "k1", serverAttemptMade = false,
            requestTag = TestRequestTag.DEFAULT,
        )
        val result2 = manager.queueCreateRequest(
            data = testItem(clientId = "c2"), httpRequest = makeRequest(),
            idempotencyKey = "k2", serverAttemptMade = false,
            requestTag = TestRequestTag.DEFAULT,
        )
        assertIs<PendingRequestQueueManager.QueueResult.Stored>(result1)
        assertIs<PendingRequestQueueManager.QueueResult.Stored>(result2)
    }

    @Test
    fun `queueCreateRequest preserves serverAttemptMade flag`() {
        val manager = createManager()
        manager.queueCreateRequest(
            data = testItem(), httpRequest = makeRequest(),
            idempotencyKey = "k1", serverAttemptMade = true,
            requestTag = TestRequestTag.DEFAULT,
        )
        val pending = manager.getPendingRequests("client-1")
        assertTrue(pending[0].serverAttemptMade)
    }

    @Test
    fun `data buoy status tracks pending requests and conflicts`() {
        val database = TestDatabaseFactory.createInMemory()
        val status = DataBuoyStatus(database)
        val manager = createManager(database = database, status = status)
        val item = testItem(name = "local")

        manager.queueCreateRequest(
            data = item,
            httpRequest = makeRequest(),
            idempotencyKey = "k1",
            serverAttemptMade = false,
            requestTag = TestRequestTag.DEFAULT,
        )

        assertEquals(1, status.pendingRequestCount.value)
        assertFalse(status.hasPendingConflicts.value)

        val mergeHandler = object : SyncableObjectRebaseHandler<TestItem>(codec) {}
        val conflictResult = manager.rebaseDataForRemainingPendingRequests(
            clientId = item.clientId,
            updatedBaseData = item.copy(name = "server"),
            mergeHandler = mergeHandler,
        )

        assertIs<PendingRequestQueueManager.RebasePendingRequestsResult.AbortedRebaseToConflicts<TestItem>>(conflictResult)
        assertEquals(1, status.pendingRequestCount.value)
        assertTrue(status.hasPendingConflicts.value)

        manager.clearAllPendingRequests(item.clientId)
        assertEquals(0, status.pendingRequestCount.value)
        assertFalse(status.hasPendingConflicts.value)
    }

    // endregion

    // region queueVoidRequest

    @Test
    fun `queueVoidRequest stores a void request`() {
        val manager = createManager()
        val item = testItem()
        val result = manager.queueVoidRequest(
            data = item, httpRequest = makeRequest(method = HttpRequest.HttpMethod.DELETE),
            idempotencyKey = "void-1", serverAttemptMade = false,
            lastSyncedServerData = item, requestTag = TestRequestTag.DEFAULT,
        )
        assertIs<PendingRequestQueueManager.QueueResult.Stored>(result)
        val pending = manager.getPendingRequests(item.clientId)
        assertEquals(1, pending.size)
        assertEquals(PendingSyncRequest.Type.VOID, pending[0].type)
    }

    @Test
    fun `queueVoidRequest preserves baseData`() {
        val manager = createManager()
        val item = testItem(name = "Original")
        val serverData = testItem(name = "ServerVersion", serverId = "s1")
        manager.queueVoidRequest(
            data = item, httpRequest = makeRequest(),
            idempotencyKey = "v1", serverAttemptMade = false,
            lastSyncedServerData = serverData, requestTag = TestRequestTag.DEFAULT,
        )
        val pending = manager.getPendingRequests(item.clientId)
        assertNotNull(pending[0].baseData)
        assertEquals("ServerVersion", pending[0].baseData!!.name)
    }

    // endregion

    // region queueUpdateRequest — Queue strategy

    @Test
    fun `queueUpdateRequest with Queue strategy stores update`() {
        val manager = createManager(strategy = PendingRequestQueueStrategy.Queue)
        val item = testItem()
        val result = manager.queueUpdateRequest(
            data = item,
            idempotencyKey = "u1",
            updateRequest = makeRequest(method = HttpRequest.HttpMethod.PUT),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Queue.Preferred(
                baseData = item,
                hasPendingRequests = false,
            ),
            requestTag = TestRequestTag.DEFAULT,
        )
        assertIs<PendingRequestQueueManager.QueueResult.Stored>(result)
        val pending = manager.getPendingRequests(item.clientId)
        assertEquals(1, pending.size)
        assertEquals(PendingSyncRequest.Type.UPDATE, pending[0].type)
    }

    @Test
    fun `queueUpdateRequest with Queue strategy allows multiple updates`() {
        val manager = createManager(strategy = PendingRequestQueueStrategy.Queue)
        val item = testItem()
        repeat(3) { i ->
            manager.queueUpdateRequest(
                data = item.copy(name = "v$i"),
                idempotencyKey = "u$i",
                updateRequest = makeRequest(),
                serverAttemptMadeForCurrentRequest = false,
                updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Queue.Preferred(
                    baseData = item,
                    hasPendingRequests = i > 0,
                ),
                requestTag = TestRequestTag.DEFAULT,
            )
        }
        assertEquals(3, manager.getPendingRequests(item.clientId).size)
    }

    @Test
    fun `queueUpdateRequest with Queue strategy rejects update after void`() {
        val manager = createManager(strategy = PendingRequestQueueStrategy.Queue)
        val item = testItem()
        manager.queueVoidRequest(
            data = item, httpRequest = makeRequest(), idempotencyKey = "v1",
            serverAttemptMade = false, lastSyncedServerData = null,
            requestTag = TestRequestTag.DEFAULT,
        )
        val result = manager.queueUpdateRequest(
            data = item,
            idempotencyKey = "u1",
            updateRequest = makeRequest(),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Queue.Preferred(
                baseData = item,
                hasPendingRequests = true,
            ),
            requestTag = TestRequestTag.DEFAULT,
        )
        assertIs<PendingRequestQueueManager.QueueResult.InvalidQueueRequest>(result)
    }

    // endregion

    // region queueUpdateRequest — Squash strategy

    @Test
    fun `squash strategy stores update when no prior pending requests`() {
        val manager = createManager(
            strategy = PendingRequestQueueStrategy.Squash(identitySquashMerger),
        )
        val item = testItem()
        val result = manager.queueUpdateRequest(
            data = item,
            idempotencyKey = "u1",
            updateRequest = makeRequest(),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Squash(
                baseData = item,
                hasPendingRequests = false,
                squashUpdateIntoCreate = identitySquashMerger,
            ),
            requestTag = TestRequestTag.DEFAULT,
        )
        assertIs<PendingRequestQueueManager.QueueResult.Stored>(result)
        assertEquals(1, manager.getPendingRequests("client-1").size)
    }

    @Test
    fun `squash strategy squashes update into unattempted create`() {
        val manager = createManager(
            strategy = PendingRequestQueueStrategy.Squash(identitySquashMerger),
        )
        val item = testItem()
        // Queue a create that has not been attempted on the server.
        manager.queueCreateRequest(
            data = item, httpRequest = makeRequestWithBody("field" to "original"),
            idempotencyKey = "create-key", serverAttemptMade = false,
            requestTag = TestRequestTag.DEFAULT,
        )
        // Queue an update — should squash into the create.
        val updateRequest = makeRequestWithBody("field" to "updated")
        manager.queueUpdateRequest(
            data = item.copy(name = "Updated"),
            idempotencyKey = "update-key",
            updateRequest = updateRequest,
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Squash(
                baseData = item,
                hasPendingRequests = true,
                squashUpdateIntoCreate = identitySquashMerger,
            ),
            requestTag = TestRequestTag.DEFAULT,
        )
        // Should still have only one pending request (the squashed create), plus the
        // newly inserted one. The squash replaces the body via storeEntry so we end up
        // with 2 rows (original create is not deleted, a new CREATE is inserted).
        val pending = manager.getPendingRequests(item.clientId)
        // The squash path calls storeEntry which inserts a new row. The original
        // create row stays. But since queueCreateRequest checks for existing creates,
        // let's verify the latest request has the updated data.
        val latest = pending.last()
        assertEquals(PendingSyncRequest.Type.CREATE, latest.type)
        assertEquals("Updated", latest.data.name)
    }

    @Test
    fun `squash strategy does not squash update into server-attempted create`() {
        val manager = createManager(
            strategy = PendingRequestQueueStrategy.Squash(identitySquashMerger),
        )
        val item = testItem()
        // Queue a create that was already attempted on the server.
        manager.queueCreateRequest(
            data = item, httpRequest = makeRequest(),
            idempotencyKey = "create-key", serverAttemptMade = true,
            requestTag = TestRequestTag.DEFAULT,
        )
        // getEffectiveUpdateContext would return Queue.ForcedAfterServerAttempt here
        // because the latest pending request was server-attempted.
        val result = manager.queueUpdateRequest(
            data = item.copy(name = "Updated"),
            idempotencyKey = "update-key",
            updateRequest = makeRequest(),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Queue.ForcedAfterServerAttempt(
                baseData = item,
                hasPendingRequests = true,
            ),
            requestTag = TestRequestTag.DEFAULT,
        )
        assertIs<PendingRequestQueueManager.QueueResult.Stored>(result)
        val pending = manager.getPendingRequests(item.clientId)
        // Should have both create and update as separate entries.
        assertEquals(2, pending.size)
        assertEquals(PendingSyncRequest.Type.CREATE, pending[0].type)
        assertEquals(PendingSyncRequest.Type.UPDATE, pending[1].type)
    }

    @Test
    fun `squash strategy squashes consecutive unattempted updates`() {
        val manager = createManager(
            strategy = PendingRequestQueueStrategy.Squash(identitySquashMerger),
        )
        val item = testItem()
        // First update.
        manager.queueUpdateRequest(
            data = item.copy(name = "v1"),
            idempotencyKey = "u1",
            updateRequest = makeRequest(),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Squash(
                baseData = item,
                hasPendingRequests = false,
                squashUpdateIntoCreate = identitySquashMerger,
            ),
            requestTag = TestRequestTag.DEFAULT,
        )
        // Second update — should squash with the first (replace via replaceEntry).
        manager.queueUpdateRequest(
            data = item.copy(name = "v2"),
            idempotencyKey = "u2",
            updateRequest = makeRequest(),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Squash(
                baseData = item,
                hasPendingRequests = true,
                squashUpdateIntoCreate = identitySquashMerger,
            ),
            requestTag = TestRequestTag.DEFAULT,
        )
        // Should still have only 1 pending request — the squashed update.
        val pending = manager.getPendingRequests(item.clientId)
        assertEquals(1, pending.size)
        assertEquals(PendingSyncRequest.Type.UPDATE, pending[0].type)
    }

    @Test
    fun `squash strategy does not squash update after server-attempted update`() {
        val manager = createManager(
            strategy = PendingRequestQueueStrategy.Squash(identitySquashMerger),
        )
        val item = testItem()
        // First update attempted on server.
        manager.queueUpdateRequest(
            data = item.copy(name = "v1"),
            idempotencyKey = "u1",
            updateRequest = makeRequest(),
            serverAttemptMadeForCurrentRequest = true,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Queue.ForcedAfterServerAttempt(
                baseData = item,
                hasPendingRequests = false,
            ),
            requestTag = TestRequestTag.DEFAULT,
        )
        // Second update — should NOT squash because the first was attempted.
        // getEffectiveUpdateContext would return Queue.ForcedAfterServerAttempt here
        // because the latest pending request was server-attempted.
        manager.queueUpdateRequest(
            data = item.copy(name = "v2"),
            idempotencyKey = "u2",
            updateRequest = makeRequest(),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Queue.ForcedAfterServerAttempt(
                baseData = item,
                hasPendingRequests = true,
            ),
            requestTag = TestRequestTag.DEFAULT,
        )
        assertEquals(2, manager.getPendingRequests(item.clientId).size)
    }

    @Test
    fun `squash strategy rejects update after void`() {
        val manager = createManager(
            strategy = PendingRequestQueueStrategy.Squash(identitySquashMerger),
        )
        val item = testItem()
        manager.queueVoidRequest(
            data = item, httpRequest = makeRequest(), idempotencyKey = "v1",
            serverAttemptMade = false, lastSyncedServerData = null,
            requestTag = TestRequestTag.DEFAULT,
        )
        val result = manager.queueUpdateRequest(
            data = item,
            idempotencyKey = "u1",
            updateRequest = makeRequest(),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Squash(
                baseData = item,
                hasPendingRequests = true,
                squashUpdateIntoCreate = identitySquashMerger,
            ),
            requestTag = TestRequestTag.DEFAULT,
        )
        assertIs<PendingRequestQueueManager.QueueResult.InvalidQueueRequest>(result)
    }

    @Test
    fun `StoreAfterServerAttempt rejects update after void in squash mode`() {
        val manager = createManager(
            strategy = PendingRequestQueueStrategy.Squash(identitySquashMerger),
        )
        val item = testItem()
        manager.queueVoidRequest(
            data = item, httpRequest = makeRequest(), idempotencyKey = "v1",
            serverAttemptMade = false, lastSyncedServerData = null,
            requestTag = TestRequestTag.DEFAULT,
        )
        val result = manager.queueUpdateRequest(
            data = item,
            idempotencyKey = "u1",
            updateRequest = makeRequest(),
            serverAttemptMadeForCurrentRequest = true,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Queue.ForcedAfterServerAttempt(
                baseData = item,
                hasPendingRequests = true,
            ),
            requestTag = TestRequestTag.DEFAULT,
        )
        assertIs<PendingRequestQueueManager.QueueResult.InvalidQueueRequest>(result)
    }

    @Test
    fun `StoreAfterServerAttempt stores with serverAttemptMade true`() {
        val manager = createManager(
            strategy = PendingRequestQueueStrategy.Squash(identitySquashMerger),
        )
        val item = testItem()
        manager.queueUpdateRequest(
            data = item,
            idempotencyKey = "u1",
            updateRequest = makeRequest(),
            serverAttemptMadeForCurrentRequest = true,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Queue.ForcedAfterServerAttempt(
                baseData = item,
                hasPendingRequests = false,
            ),
            requestTag = TestRequestTag.DEFAULT,
        )
        val pending = manager.getPendingRequests(item.clientId)
        assertEquals(1, pending.size)
        assertTrue(pending[0].serverAttemptMade)
    }

    // endregion

    // region hasPendingRequests

    @Test
    fun `hasPendingRequests returns false when empty`() {
        val manager = createManager()
        assertFalse(manager.hasPendingRequests("client-1"))
    }

    @Test
    fun `hasPendingRequests returns true when requests exist`() {
        val manager = createManager()
        manager.queueCreateRequest(
            data = testItem(), httpRequest = makeRequest(),
            idempotencyKey = "k1", serverAttemptMade = false,
            requestTag = TestRequestTag.DEFAULT,
        )
        assertTrue(manager.hasPendingRequests("client-1"))
    }

    @Test
    fun `hasPendingRequests is scoped to clientId`() {
        val manager = createManager()
        manager.queueCreateRequest(
            data = testItem(clientId = "c1"), httpRequest = makeRequest(),
            idempotencyKey = "k1", serverAttemptMade = false,
            requestTag = TestRequestTag.DEFAULT,
        )
        assertTrue(manager.hasPendingRequests("c1"))
        assertFalse(manager.hasPendingRequests("c2"))
    }

    // endregion

    // region getLatestPendingRequest

    @Test
    fun `getLatestPendingRequest returns null when no requests`() {
        val manager = createManager()
        assertNull(manager.getLatestPendingRequest("client-1"))
    }

    @Test
    fun `getLatestPendingRequest returns the last queued request`() {
        val manager = createManager(strategy = PendingRequestQueueStrategy.Queue)
        val item = testItem()
        manager.queueCreateRequest(
            data = item, httpRequest = makeRequest(), idempotencyKey = "k1",
            serverAttemptMade = false, requestTag = TestRequestTag.DEFAULT,
        )
        manager.queueUpdateRequest(
            data = item.copy(name = "Updated"), idempotencyKey = "k2",
            updateRequest = makeRequest(),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Queue.Preferred(
                baseData = item,
                hasPendingRequests = true,
            ),
            requestTag = TestRequestTag.DEFAULT,
        )
        val latest = manager.getLatestPendingRequest(item.clientId)
        assertNotNull(latest)
        assertEquals(PendingSyncRequest.Type.UPDATE, latest.type)
        assertEquals("Updated", latest.data.name)
    }

    // endregion

    // region getPendingRequestById

    @Test
    fun `getPendingRequestById returns null for nonexistent id`() {
        val manager = createManager()
        assertNull(manager.getPendingRequestById(999))
    }

    @Test
    fun `getPendingRequestById returns the correct request`() {
        val manager = createManager()
        manager.queueCreateRequest(
            data = testItem(), httpRequest = makeRequest(), idempotencyKey = "k1",
            serverAttemptMade = false, requestTag = TestRequestTag.DEFAULT,
        )
        val pending = manager.getPendingRequests("client-1")
        val found = manager.getPendingRequestById(pending[0].pendingRequestId)
        assertNotNull(found)
        assertEquals("k1", found.idempotencyKey)
    }

    // endregion

    // region getAllPendingRequests

    @Test
    fun `getPendingRequests without clientId returns all for service`() {
        val manager = createManager()
        manager.queueCreateRequest(
            data = testItem(clientId = "c1"), httpRequest = makeRequest(),
            idempotencyKey = "k1", serverAttemptMade = false,
            requestTag = TestRequestTag.DEFAULT,
        )
        manager.queueCreateRequest(
            data = testItem(clientId = "c2"), httpRequest = makeRequest(),
            idempotencyKey = "k2", serverAttemptMade = false,
            requestTag = TestRequestTag.DEFAULT,
        )
        assertEquals(2, manager.getPendingRequests().size)
    }

    @Test
    fun `getPendingRequests is scoped to serviceName`() {
        val db = TestDatabaseFactory.createInMemory()
        val manager1 = createManager(database = db, serviceName = "service-a")
        val manager2 = createManager(database = db, serviceName = "service-b")
        manager1.queueCreateRequest(
            data = testItem(), httpRequest = makeRequest(),
            idempotencyKey = "k1", serverAttemptMade = false,
            requestTag = TestRequestTag.DEFAULT,
        )
        manager2.queueCreateRequest(
            data = testItem(), httpRequest = makeRequest(),
            idempotencyKey = "k2", serverAttemptMade = false,
            requestTag = TestRequestTag.DEFAULT,
        )
        assertEquals(1, manager1.getPendingRequests().size)
        assertEquals(1, manager2.getPendingRequests().size)
    }

    // endregion

    // region clearAllPendingRequests

    @Test
    fun `clearAllPendingRequests removes all requests for clientId`() {
        val manager = createManager(strategy = PendingRequestQueueStrategy.Queue)
        val item = testItem()
        manager.queueCreateRequest(
            data = item, httpRequest = makeRequest(), idempotencyKey = "k1",
            serverAttemptMade = false, requestTag = TestRequestTag.DEFAULT,
        )
        manager.queueUpdateRequest(
            data = item.copy(name = "v2"), idempotencyKey = "k2",
            updateRequest = makeRequest(),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Queue.Preferred(
                baseData = item,
                hasPendingRequests = true,
            ),
            requestTag = TestRequestTag.DEFAULT,
        )
        assertEquals(2, manager.getPendingRequests(item.clientId).size)
        manager.clearAllPendingRequests(item.clientId)
        assertEquals(0, manager.getPendingRequests(item.clientId).size)
    }

    @Test
    fun `clearAllPendingRequests does not affect other clientIds`() {
        val manager = createManager()
        manager.queueCreateRequest(
            data = testItem(clientId = "c1"), httpRequest = makeRequest(),
            idempotencyKey = "k1", serverAttemptMade = false,
            requestTag = TestRequestTag.DEFAULT,
        )
        manager.queueCreateRequest(
            data = testItem(clientId = "c2"), httpRequest = makeRequest(),
            idempotencyKey = "k2", serverAttemptMade = false,
            requestTag = TestRequestTag.DEFAULT,
        )
        manager.clearAllPendingRequests("c1")
        assertFalse(manager.hasPendingRequests("c1"))
        assertTrue(manager.hasPendingRequests("c2"))
    }

    // endregion

    // region clearPendingRequestAfterUpload

    @Test
    fun `clearPendingRequestAfterUpload removes the request and returns SYNCED when none remain`() {
        val manager = createManager()
        manager.queueCreateRequest(
            data = testItem(), httpRequest = makeRequest(), idempotencyKey = "k1",
            serverAttemptMade = false, requestTag = TestRequestTag.DEFAULT,
        )
        val pending = manager.getPendingRequests("client-1")
        val result = manager.clearPendingRequestAfterUpload(
            pendingRequestId = pending[0].pendingRequestId,
            clientId = "client-1",
        )
        assertIs<PendingRequestQueueManager.ClearRequestResult.Cleared>(result)
        assertEquals(SyncableObject.SyncStatus.SYNCED, result.updatedSyncStatus)
        assertEquals(0, manager.getPendingRequests("client-1").size)
    }

    @Test
    fun `clearPendingRequestAfterUpload returns PENDING_UPDATE when update remains`() {
        val manager = createManager(strategy = PendingRequestQueueStrategy.Queue)
        val item = testItem()
        manager.queueCreateRequest(
            data = item, httpRequest = makeRequest(), idempotencyKey = "k1",
            serverAttemptMade = false, requestTag = TestRequestTag.DEFAULT,
        )
        manager.queueUpdateRequest(
            data = item.copy(name = "v2"), idempotencyKey = "k2",
            updateRequest = makeRequest(),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Queue.Preferred(
                baseData = item,
                hasPendingRequests = true,
            ),
            requestTag = TestRequestTag.DEFAULT,
        )
        val pending = manager.getPendingRequests(item.clientId)
        val result = manager.clearPendingRequestAfterUpload(
            pendingRequestId = pending[0].pendingRequestId,
            clientId = item.clientId,
        )
        assertIs<PendingRequestQueueManager.ClearRequestResult.Cleared>(result)
        assertEquals(SyncableObject.SyncStatus.PENDING_UPDATE, result.updatedSyncStatus)
    }

    @Test
    fun `clearPendingRequestAfterUpload returns PENDING_VOID when void remains`() {
        val manager = createManager(strategy = PendingRequestQueueStrategy.Queue)
        val item = testItem()
        manager.queueCreateRequest(
            data = item, httpRequest = makeRequest(), idempotencyKey = "k1",
            serverAttemptMade = false, requestTag = TestRequestTag.DEFAULT,
        )
        manager.queueVoidRequest(
            data = item, httpRequest = makeRequest(), idempotencyKey = "v1",
            serverAttemptMade = false, lastSyncedServerData = null,
            requestTag = TestRequestTag.DEFAULT,
        )
        val pending = manager.getPendingRequests(item.clientId)
        val result = manager.clearPendingRequestAfterUpload(
            pendingRequestId = pending[0].pendingRequestId,
            clientId = item.clientId,
        )
        assertIs<PendingRequestQueueManager.ClearRequestResult.Cleared>(result)
        assertEquals(SyncableObject.SyncStatus.PENDING_VOID, result.updatedSyncStatus)
    }

    // endregion

    // region markPendingRequestAsAttempted

    @Test
    fun `markPendingRequestAsAttempted sets serverAttemptMade to true`() {
        val manager = createManager()
        manager.queueCreateRequest(
            data = testItem(), httpRequest = makeRequest(), idempotencyKey = "k1",
            serverAttemptMade = false, requestTag = TestRequestTag.DEFAULT,
        )
        val pending = manager.getPendingRequests("client-1")
        assertFalse(pending[0].serverAttemptMade)
        manager.markPendingRequestAsAttempted(pending[0].pendingRequestId)
        val updated = manager.getPendingRequests("client-1")
        assertTrue(updated[0].serverAttemptMade)
    }

    // endregion

    // region rebaseDataForRemainingPendingRequests

    @Test
    fun `rebase returns NoPendingRequestRemaining when queue is empty`() {
        val manager = createManager()
        val mergeHandler = SyncableObjectRebaseHandler(codec)
        val result = manager.rebaseDataForRemainingPendingRequests(
            clientId = "client-1",
            updatedBaseData = testItem(),
            mergeHandler = mergeHandler,
        )
        assertIs<PendingRequestQueueManager.RebasePendingRequestsResult.NoPendingRequestRemaining<TestItem>>(result)
    }

    @Test
    fun `rebase applies server changes to pending request without conflict`() {
        val manager = createManager(strategy = PendingRequestQueueStrategy.Queue)
        val base = testItem(name = "Base", value = 1)
        val localUpdate = testItem(name = "LocalName", value = 1)
        // Queue an update that changed the name field.
        manager.queueUpdateRequest(
            data = localUpdate,
            idempotencyKey = "u1",
            updateRequest = makeRequest(),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Queue.Preferred(
                baseData = base,
                hasPendingRequests = false,
            ),
            requestTag = TestRequestTag.DEFAULT,
        )
        // Server changed the value field (no conflict with name change).
        val serverUpdate = testItem(name = "Base", value = 99)
        val mergeHandler = SyncableObjectRebaseHandler(codec)
        val result = manager.rebaseDataForRemainingPendingRequests(
            clientId = "client-1",
            updatedBaseData = serverUpdate,
            mergeHandler = mergeHandler,
        )
        assertIs<PendingRequestQueueManager.RebasePendingRequestsResult.RebasedRemainingPendingRequests<TestItem>>(result)
        assertEquals("LocalName", result.rebasedLatestData.name)
        assertEquals(99, result.rebasedLatestData.value)
    }

    @Test
    fun `rebase aborts with conflict when same field changed differently`() {
        val manager = createManager(strategy = PendingRequestQueueStrategy.Queue)
        val base = testItem(name = "Base", value = 1)
        val localUpdate = testItem(name = "LocalName", value = 1)
        manager.queueUpdateRequest(
            data = localUpdate,
            idempotencyKey = "u1",
            updateRequest = makeRequest(),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Queue.Preferred(
                baseData = base,
                hasPendingRequests = false,
            ),
            requestTag = TestRequestTag.DEFAULT,
        )
        // Server also changed name to a different value → conflict.
        val serverUpdate = testItem(name = "ServerName", value = 1)
        val mergeHandler = SyncableObjectRebaseHandler(codec)
        val result = manager.rebaseDataForRemainingPendingRequests(
            clientId = "client-1",
            updatedBaseData = serverUpdate,
            mergeHandler = mergeHandler,
        )
        assertIs<PendingRequestQueueManager.RebasePendingRequestsResult.AbortedRebaseToConflicts<TestItem>>(result)
        assertTrue(result.conflict.fieldNames.contains("name"))
    }

    // endregion

    // region conflict helpers

    @Test
    fun `getConflictingPendingRequest returns null when no conflicts`() {
        val manager = createManager()
        manager.queueCreateRequest(
            data = testItem(), httpRequest = makeRequest(), idempotencyKey = "k1",
            serverAttemptMade = false, requestTag = TestRequestTag.DEFAULT,
        )
        assertNull(manager.getConflictingPendingRequest("client-1"))
    }

    @Test
    fun `hasAnyConflictsGlobally returns false when no conflicts exist`() {
        val manager = createManager()
        manager.queueCreateRequest(
            data = testItem(), httpRequest = makeRequest(), idempotencyKey = "k1",
            serverAttemptMade = false, requestTag = TestRequestTag.DEFAULT,
        )
        assertFalse(manager.hasAnyConflictsGlobally())
    }

    @Test
    fun `getConflicts returns empty list when no conflicts`() {
        val manager = createManager()
        assertTrue(manager.getConflicts("client-1").isEmpty())
    }

    // endregion

    // region resolveConflictOnPendingRequest

    @Test
    fun `resolveConflictOnPendingRequest clears conflict and updates data`() {
        val manager = createManager(strategy = PendingRequestQueueStrategy.Queue)
        val base = testItem(name = "Base", value = 1)
        val localUpdate = testItem(name = "LocalName", value = 1)
        manager.queueUpdateRequest(
            data = localUpdate,
            idempotencyKey = "u1",
            updateRequest = makeRequest(),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Queue.Preferred(
                baseData = base,
                hasPendingRequests = false,
            ),
            requestTag = TestRequestTag.DEFAULT,
        )

        // Trigger a conflict via rebase.
        val serverUpdate = testItem(name = "ServerName", value = 1)
        val mergeHandler = SyncableObjectRebaseHandler(codec)
        manager.rebaseDataForRemainingPendingRequests(
            clientId = "client-1",
            updatedBaseData = serverUpdate,
            mergeHandler = mergeHandler,
        )

        // There should now be a conflict.
        val conflicting = manager.getConflictingPendingRequest("client-1")
        assertNotNull(conflicting)
        assertNotNull(conflicting.conflict)

        // Resolve the conflict.
        val resolved = testItem(name = "ResolvedName", value = 1)
        manager.resolveConflictOnPendingRequest(
            pendingRequest = conflicting,
            resolvedData = resolved,
            resolvedHttpRequest = makeRequest(),
            newServerBaseline = serverUpdate,
        )

        // Conflict should be cleared.
        assertNull(manager.getConflictingPendingRequest("client-1"))
        val updated = manager.getPendingRequests("client-1")
        assertEquals(1, updated.size)
        assertEquals("ResolvedName", updated[0].data.name)
    }

    // endregion

    // region data round-trip through database

    @Test
    fun `pending request data survives database round-trip`() {
        val manager = createManager()
        val item = testItem(
            clientId = "c1", serverId = "s1", version = 3,
            name = "RoundTrip", value = 42,
        )
        val request = HttpRequest(
            method = HttpRequest.HttpMethod.PUT,
            endpointUrl = "https://api.test.com/items/s1",
            requestBody = buildJsonObject { put("name", "RoundTrip") },
            additionalHeaders = listOf("X-Custom" to "header-val"),
        )
        manager.queueUpdateRequest(
            data = item,
            idempotencyKey = "rt-key",
            updateRequest = request,
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Queue.Preferred(
                baseData = item.copy(name = "Original"),
                hasPendingRequests = false,
            ),
            requestTag = TestRequestTag.ALTERNATE,
        )
        val pending = manager.getPendingRequests("c1")
        assertEquals(1, pending.size)
        val p = pending[0]
        assertEquals(PendingSyncRequest.Type.UPDATE, p.type)
        assertEquals("rt-key", p.idempotencyKey)
        assertEquals("RoundTrip", p.data.name)
        assertEquals(42, p.data.value)
        assertEquals(HttpRequest.HttpMethod.PUT, p.request.method)
        assertEquals("https://api.test.com/items/s1", p.request.endpointUrl)
        assertEquals("Original", p.baseData?.name)
        assertEquals("alternate", p.requestTag)
    }

    // endregion
}
