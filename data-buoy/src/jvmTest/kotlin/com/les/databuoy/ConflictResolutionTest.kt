package com.les.databuoy

import com.les.databuoy.db.SyncDatabase
import com.les.databuoy.managers.LocalStoreManager
import com.les.databuoy.serviceconfigs.SyncableObjectRebaseHandler
import com.les.databuoy.sync.SyncScheduleNotifier
import com.les.databuoy.sync.UpsertResult
import com.les.databuoy.syncableobjectservicedatatypes.HttpRequest
import com.les.databuoy.syncableobjectservicedatatypes.ResolveConflictResult
import com.les.databuoy.testing.NoOpSyncScheduleNotifier
import com.les.databuoy.testing.TestDatabaseFactory
import com.les.databuoy.utils.SyncCodec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the conflict resolution flow:
 *
 * 1. An offline update is queued as a pending request.
 * 2. A sync-down receives server data that conflicts with the pending update.
 * 3. The rebase detects field-level conflicts and marks the row as CONFLICT.
 * 4. The consumer resolves the conflict by providing resolved data.
 * 5. The row transitions back to a pending state and sync-up can proceed.
 */
class ConflictResolutionTest {

    // region helpers

    private enum class TestRequestTag(override val value: String) : ServiceRequestTag {
        DEFAULT("default"),
    }

    private val noOpNotifier: SyncScheduleNotifier = NoOpSyncScheduleNotifier

    private val codec = SyncCodec(TestItem.serializer())

    private fun testItem(
        clientId: String,
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

    private fun createLocalStore(
        database: SyncDatabase,
        serviceName: String = "test",
    ) = LocalStoreManager<TestItem, TestRequestTag>(
        database = database,
        serviceName = serviceName,
        syncScheduleNotifier = noOpNotifier,
        codec = codec,
    )

    private val mergeHandler = SyncableObjectRebaseHandler(codec)

    // endregion

    /**
     * Verifies the full conflict → resolution flow:
     *
     * 1. Seed a synced row (server_id = "srv-1", name = "Original", value = 10)
     * 2. Queue a local UPDATE changing name to "LocalEdit"
     * 3. Simulate server changing name to "ServerEdit" via rebase
     * 4. Verify the row enters CONFLICT status with correct field info
     * 5. Resolve the conflict by choosing the local name with the server value
     * 6. Verify the row transitions back to PENDING_UPDATE
     * 7. Verify the pending request has no conflict and contains the resolved data
     */
    @Test
    fun `resolveConflictData clears conflict and restores pending state`() {
        val db = TestDatabaseFactory.createInMemory()
        val localStore = createLocalStore(db)

        // Step 1: Seed a synced row.
        val syncedItem = testItem(
            clientId = "c1",
            serverId = "srv-1",
            version = 1,
            name = "Original",
            value = 10,
        )
        localStore.upsertEntry(
            serverObj = syncedItem,
            syncedAtTimestamp = "1000",
            clientId = "c1",
        )

        // Step 2: Queue a local UPDATE that changes `name` (keep version = 1 to match base).
        val localUpdate = syncedItem.copy(name = "LocalEdit")
        localStore.updateLocalData(
            data = localUpdate,
            idempotencyKey = "idem-1",
            updateRequest = makeRequest(
                method = HttpRequest.HttpMethod.PUT,
                endpoint = "https://api.test.com/items/srv-1",
                body = buildJsonObject { put("name", "LocalEdit") },
            ),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Queue.Preferred(
                baseData = syncedItem,
                hasPendingRequests = false,
            ),
            requestTag = TestRequestTag.DEFAULT,
        )

        // Step 3: Simulate server changing the same field — triggers a conflict on rebase.
        val serverUpdate = syncedItem.copy(name = "ServerEdit", version = 2)
        val upsertResult = localStore.upsertSyncDownResponseData(
            clientId = "c1",
            lastSyncedTimestamp = "2000",
            updatedServerData = serverUpdate,
            mergeHandler = mergeHandler,
        )
        assertIs<UpsertResult.ConflictFailure>(upsertResult,
            "Rebase should have detected a conflict")

        // Step 4: Verify the row is in CONFLICT status.
        val conflictedEntry = localStore.getData(clientId = "c1", serverId = "srv-1")!!
        assertIs<SyncableObject.SyncStatus.Conflict>(conflictedEntry.syncStatus,
            "sync_data row should be in CONFLICT status")

        val conflictInfo = (conflictedEntry.syncStatus as SyncableObject.SyncStatus.Conflict).conflictInfo
        assertTrue(conflictInfo.any { it.fieldName == "name" },
            "Conflict should include the 'name' field")

        // Verify the pending request has conflict info.
        val conflictingRequest = localStore.pendingRequestQueueManager
            .getConflictingPendingRequest("c1")!!
        assertTrue(conflictingRequest.conflict != null,
            "Pending request should have conflict info")
        assertTrue(conflictingRequest.conflict!!.fieldNames.contains("name"),
            "Conflict field names should include 'name'")

        // Step 5: Resolve the conflict — keep local name, accept server version.
        val resolvedData = testItem(
            clientId = "c1",
            serverId = "srv-1",
            version = 2,
            name = "LocalEdit",
            value = 10,
        )
        val resolvedRequest = makeRequest(
            method = HttpRequest.HttpMethod.PUT,
            endpoint = "https://api.test.com/items/srv-1",
            body = buildJsonObject { put("name", "LocalEdit") },
        )

        val resolveResult = localStore.resolveConflictData(
            resolvedData = resolvedData,
            resolvedHttpRequest = resolvedRequest,
            mergeHandler = mergeHandler,
        )

        // Step 6: Verify the result and sync_data status.
        assertIs<ResolveConflictResult.Resolved<TestItem>>(resolveResult,
            "Resolution should succeed")

        val resolvedEntry = localStore.getData(clientId = "c1", serverId = "srv-1")!!
        assertIs<SyncableObject.SyncStatus.PendingUpdate>(resolvedEntry.syncStatus,
            "sync_data should return to PENDING_UPDATE after conflict resolution")

        // Step 7: Verify the pending request is updated and conflict-free.
        val pendingRequests = localStore.pendingRequestQueueManager.getPendingRequests("c1")
        assertEquals(1, pendingRequests.size, "Should still have one pending request")
        assertNull(pendingRequests[0].conflict,
            "Pending request should have no conflict after resolution")
        assertEquals("LocalEdit", pendingRequests[0].data.name,
            "Pending request data should contain the resolved values")
    }

    /**
     * Verifies that resolveConflictData self-heals when called on a row that is not
     * in CONFLICT status — no conflicting pending request exists, so the repair path
     * runs and returns Resolved with the current data.
     */
    @Test
    fun `resolveConflictData self-heals when not in conflict`() {
        val db = TestDatabaseFactory.createInMemory()
        val localStore = createLocalStore(db)

        // Seed a synced row (not in conflict).
        val syncedItem = testItem(
            clientId = "c1",
            serverId = "srv-1",
            version = 1,
            name = "Original",
            value = 10,
        )
        localStore.upsertEntry(
            serverObj = syncedItem,
            syncedAtTimestamp = "1000",
            clientId = "c1",
        )

        val result = localStore.resolveConflictData(
            resolvedData = syncedItem,
            resolvedHttpRequest = makeRequest(),
            mergeHandler = mergeHandler,
        )

        assertIs<ResolveConflictResult.Resolved<TestItem>>(result,
            "Should self-heal and return Resolved when row is not in CONFLICT status")
    }

    /**
     * Verifies that repairOrphanedConflictStatus self-heals a sync_data row stuck in
     * CONFLICT when no pending request has conflict_info. The row should transition
     * back to SYNCED if there are no pending requests.
     */
    @Test
    fun `repairOrphanedConflictStatus restores SYNCED when no pending requests exist`() {
        val db = TestDatabaseFactory.createInMemory()
        val localStore = createLocalStore(db)

        // Seed a synced row, then manually mark it as CONFLICT to simulate the orphaned state.
        val syncedItem = testItem(
            clientId = "c1",
            serverId = "srv-1",
            version = 1,
            name = "Original",
            value = 10,
        )
        localStore.upsertEntry(
            serverObj = syncedItem,
            syncedAtTimestamp = "1000",
            clientId = "c1",
        )
        db.syncDataQueries.resolveConflict(
            sync_status = SyncableObject.SyncStatus.CONFLICT,
            data_blob = codec.encode(syncedItem).toString(),
            server_id = "srv-1",
            service_name = "test",
            client_id = "c1",
        )

        // Verify it's in CONFLICT.
        val beforeRepair = localStore.getData(clientId = "c1", serverId = "srv-1")!!
        assertIs<SyncableObject.SyncStatus.Conflict>(beforeRepair.syncStatus)

        // Repair.
        val result = localStore.repairOrphanedConflictStatus(
            clientId = "c1",
            serverId = "srv-1",
            mergeHandler = mergeHandler,
        )

        assertIs<ResolveConflictResult.Resolved<TestItem>>(result,
            "Repair should succeed")

        val afterRepair = localStore.getData(clientId = "c1", serverId = "srv-1")!!
        assertIs<SyncableObject.SyncStatus.Synced>(afterRepair.syncStatus,
            "sync_data should be SYNCED after repair with no pending requests")
    }

    /**
     * Verifies that repairOrphanedConflictStatus restores PENDING_UPDATE when pending
     * requests exist but none have conflict_info.
     */
    @Test
    fun `repairOrphanedConflictStatus restores pending state when requests exist without conflicts`() {
        val db = TestDatabaseFactory.createInMemory()
        val localStore = createLocalStore(db)

        // Seed a synced row.
        val syncedItem = testItem(
            clientId = "c1",
            serverId = "srv-1",
            version = 1,
            name = "Original",
            value = 10,
        )
        localStore.upsertEntry(
            serverObj = syncedItem,
            syncedAtTimestamp = "1000",
            clientId = "c1",
        )

        // Queue a non-conflicting UPDATE.
        val update = syncedItem.copy(name = "Updated")
        localStore.updateLocalData(
            data = update,
            idempotencyKey = "idem-1",
            updateRequest = makeRequest(
                method = HttpRequest.HttpMethod.PUT,
                body = buildJsonObject { put("name", "Updated") },
            ),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Queue.Preferred(
                baseData = syncedItem,
                hasPendingRequests = false,
            ),
            requestTag = TestRequestTag.DEFAULT,
        )

        // Manually mark sync_data as CONFLICT to simulate orphaned state.
        db.syncDataQueries.resolveConflict(
            sync_status = SyncableObject.SyncStatus.CONFLICT,
            data_blob = codec.encode(update).toString(),
            server_id = "srv-1",
            service_name = "test",
            client_id = "c1",
        )

        // Repair.
        val result = localStore.repairOrphanedConflictStatus(
            clientId = "c1",
            serverId = "srv-1",
            mergeHandler = mergeHandler,
        )

        assertIs<ResolveConflictResult.Resolved<TestItem>>(result,
            "Repair should succeed")

        val afterRepair = localStore.getData(clientId = "c1", serverId = "srv-1")!!
        assertIs<SyncableObject.SyncStatus.PendingUpdate>(afterRepair.syncStatus,
            "sync_data should be PENDING_UPDATE after repair with pending requests")
    }

    /**
     * Verifies that when there are multiple pending requests and only the first
     * one conflicts, resolving the conflict also re-rebases the subsequent ones.
     */
    @Test
    fun `resolveConflictData re-rebases subsequent pending requests`() {
        val db = TestDatabaseFactory.createInMemory()
        val localStore = createLocalStore(db)

        // Seed a synced row.
        val syncedItem = testItem(
            clientId = "c1",
            serverId = "srv-1",
            version = 1,
            name = "Original",
            value = 10,
        )
        localStore.upsertEntry(
            serverObj = syncedItem,
            syncedAtTimestamp = "1000",
            clientId = "c1",
        )

        // Queue UPDATE 1: change name (keep version = 1 to match base).
        val update1 = syncedItem.copy(name = "LocalEdit1")
        localStore.updateLocalData(
            data = update1,
            idempotencyKey = "idem-1",
            updateRequest = makeRequest(
                method = HttpRequest.HttpMethod.PUT,
                body = buildJsonObject { put("name", "LocalEdit1") },
            ),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Queue.Preferred(
                baseData = syncedItem,
                hasPendingRequests = false,
            ),
            requestTag = TestRequestTag.DEFAULT,
        )

        // Queue UPDATE 2: change value (non-conflicting field, keep version = 1).
        val update2 = update1.copy(value = 99)
        localStore.updateLocalData(
            data = update2,
            idempotencyKey = "idem-2",
            updateRequest = makeRequest(
                method = HttpRequest.HttpMethod.PUT,
                body = buildJsonObject { put("value", 99) },
            ),
            serverAttemptMadeForCurrentRequest = false,
            updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Queue.Preferred(
                baseData = update1,
                hasPendingRequests = true,
            ),
            requestTag = TestRequestTag.DEFAULT,
        )

        // Simulate server changing `name` — conflicts with UPDATE 1.
        val serverUpdate = syncedItem.copy(name = "ServerEdit", version = 2)
        val upsertResult = localStore.upsertSyncDownResponseData(
            clientId = "c1",
            lastSyncedTimestamp = "2000",
            updatedServerData = serverUpdate,
            mergeHandler = mergeHandler,
        )
        assertIs<UpsertResult.ConflictFailure>(upsertResult)

        // Verify conflict exists.
        val pendingBefore = localStore.pendingRequestQueueManager.getPendingRequests("c1")
        // Only the first pending request should have conflict_info (rebase aborts at first conflict).
        assertTrue(pendingBefore[0].conflict != null, "First pending request should have conflict")

        // Resolve the conflict.
        val resolvedData = testItem(
            clientId = "c1",
            serverId = "srv-1",
            version = 2,
            name = "LocalEdit1", // keep local name
            value = 10,
        )
        val resolveResult = localStore.resolveConflictData(
            resolvedData = resolvedData,
            resolvedHttpRequest = makeRequest(
                method = HttpRequest.HttpMethod.PUT,
                body = buildJsonObject { put("name", "LocalEdit1") },
            ),
            mergeHandler = mergeHandler,
        )

        assertIs<ResolveConflictResult.Resolved<TestItem>>(resolveResult)

        // Verify both pending requests are now conflict-free.
        val pendingAfter = localStore.pendingRequestQueueManager.getPendingRequests("c1")
        assertEquals(2, pendingAfter.size, "Both pending requests should still exist")
        assertNull(pendingAfter[0].conflict, "First pending request should be conflict-free")
        assertNull(pendingAfter[1].conflict, "Second pending request should be conflict-free")

        // Verify sync_data is back to PENDING_UPDATE.
        val entry = localStore.getData(clientId = "c1", serverId = "srv-1")!!
        assertIs<SyncableObject.SyncStatus.PendingUpdate>(entry.syncStatus)
    }
}
