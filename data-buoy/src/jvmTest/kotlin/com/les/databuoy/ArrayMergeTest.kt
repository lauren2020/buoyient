package com.les.databuoy

import com.les.databuoy.serviceconfigs.SyncableObjectRebaseHandler
import com.les.databuoy.syncableobjectservicedatatypes.HttpRequest
import com.les.databuoy.utils.SyncCodec
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Tests that the 3-way merge treats concurrent array additions as a merge
 * rather than a conflict.
 *
 * Scenario:
 * - Base:   tags = ["a"]
 * - Local:  tags = ["a", "b"]   (local added "b")
 * - Server: tags = ["a", "c"]   (server added "c")
 *
 * Expected after fix: merged tags = ["a", "b", "c"] — no conflict.
 * Before the fix this was reported as a field-level conflict.
 */
class ArrayMergeTest {

    private val codec = SyncCodec(TestItem.serializer())
    private val mergeHandler = SyncableObjectRebaseHandler(codec)

    private fun testItem(
        clientId: String = "c1",
        serverId: String? = "srv-1",
        version: Int = 1,
        name: String = "Test",
        value: Int = 0,
        tags: List<String> = emptyList(),
        syncStatus: SyncableObject.SyncStatus = SyncableObject.SyncStatus.LocalOnly,
    ) = TestItem(serverId, clientId, version, syncStatus, name, value, tags)

    private fun makeRequest(
        body: JsonObject = JsonObject(emptyMap()),
    ) = HttpRequest(
        method = HttpRequest.HttpMethod.PUT,
        endpointUrl = "https://api.test.com/items/srv-1",
        requestBody = body,
    )

    /**
     * Both sides adding to an array should produce a merged result containing
     * the union of additions, not a conflict.
     */
    @Test
    fun `concurrent array additions are merged without conflict`() {
        val base = testItem(tags = listOf("a"))
        val local = testItem(tags = listOf("a", "b"))
        val server = testItem(tags = listOf("a", "c"))

        val result = mergeHandler.rebaseDataForPendingRequest(
            oldBaseData = base,
            currentData = local,
            newBaseData = server,
            pendingHttpRequest = makeRequest(),
            pendingRequestId = 1,
            requestTag = "default",
        )

        assertIs<SyncableObjectRebaseHandler.RebaseResult.Rebased<TestItem>>(result,
            "Concurrent array additions should merge without conflict")

        val merged = (result as SyncableObjectRebaseHandler.RebaseResult.Rebased).mergedData
        assertEquals(listOf("a", "b", "c"), merged.tags,
            "Merged tags should contain base + local additions + server additions")
    }

    /**
     * When one side adds and the other removes from an array, that IS a conflict.
     */
    @Test
    fun `concurrent array add and remove is still a conflict`() {
        val base = testItem(tags = listOf("a", "b"))
        val local = testItem(tags = listOf("a", "b", "c"))   // added "c"
        val server = testItem(tags = listOf("a"))              // removed "b"

        val result = mergeHandler.rebaseDataForPendingRequest(
            oldBaseData = base,
            currentData = local,
            newBaseData = server,
            pendingHttpRequest = makeRequest(),
            pendingRequestId = 1,
            requestTag = "default",
        )

        assertIs<SyncableObjectRebaseHandler.RebaseResult.Conflict<TestItem>>(result,
            "Add vs remove should still be a conflict")
    }

    /**
     * When both sides add the same elements, no conflict (already handled by
     * the localStr == serverStr check).
     */
    @Test
    fun `both sides adding identical elements is not a conflict`() {
        val base = testItem(tags = listOf("a"))
        val local = testItem(tags = listOf("a", "b"))
        val server = testItem(tags = listOf("a", "b"))

        val result = mergeHandler.rebaseDataForPendingRequest(
            oldBaseData = base,
            currentData = local,
            newBaseData = server,
            pendingHttpRequest = makeRequest(),
            pendingRequestId = 1,
            requestTag = "default",
        )

        assertIs<SyncableObjectRebaseHandler.RebaseResult.Rebased<TestItem>>(result)
    }

    /**
     * Array merge with a null base (new field) — both sides introduce the field
     * with different array values. Both are pure additions, so they should merge.
     */
    @Test
    fun `array merge with null base merges both additions`() {
        val base = testItem(tags = emptyList())
        val local = testItem(tags = listOf("x"))
        val server = testItem(tags = listOf("y"))

        val result = mergeHandler.rebaseDataForPendingRequest(
            oldBaseData = base,
            currentData = local,
            newBaseData = server,
            pendingHttpRequest = makeRequest(),
            pendingRequestId = 1,
            requestTag = "default",
        )

        assertIs<SyncableObjectRebaseHandler.RebaseResult.Rebased<TestItem>>(result,
            "Both adding to an empty array should merge")

        val merged = (result as SyncableObjectRebaseHandler.RebaseResult.Rebased).mergedData
        assertEquals(listOf("x", "y"), merged.tags,
            "Merged should contain local additions then server additions")
    }
}
