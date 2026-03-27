package com.les.buoyient

import com.les.buoyient.serviceconfigs.SyncableObjectRebaseHandler
import com.les.buoyient.datatypes.HttpRequest
import com.les.buoyient.utils.SyncCodec
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
        version: String? = "1",
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

        val merged = result.mergedData
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

        val merged = result.mergedData
        assertEquals(listOf("x", "y"), merged.tags,
            "Merged should contain local additions then server additions")
    }

    // region Server-enriched nested objects

    private val orderCodec = SyncCodec(TestOrder.serializer())
    private val orderMergeHandler = SyncableObjectRebaseHandler(orderCodec)

    private fun testOrder(
        clientId: String = "c1",
        serverId: String? = null,
        version: String? = null,
        name: String = "Order-1",
        items: List<LineItem> = emptyList(),
    ) = TestOrder(serverId, clientId, version, SyncableObject.SyncStatus.LocalOnly, name, items)

    private fun makeOrderRequest(
        body: JsonObject = JsonObject(emptyMap()),
    ) = HttpRequest(
        method = HttpRequest.HttpMethod.PUT,
        endpointUrl = "https://api.test.com/orders/srv-1",
        requestBody = body,
    )

    /**
     * Reproduces the bug from manual testing:
     *
     * 1. Device offline — create an order with 1 line item
     * 2. Update the order by adding a second line item (first unchanged)
     * 3. Device online — create syncs; server assigns IDs to the parent AND the line item
     * 4. Rebase the pending update against the enriched server data
     *
     * Before the fix, the rebase reports a conflict on "items" because the
     * set-based element comparison fails — the server-enriched line item
     * (with a server_id) doesn't match the base line item (server_id = null).
     *
     * Expected: merge succeeds, taking the server's enriched first item
     * and appending the locally-added second item.
     */
    @Test
    fun `server-enriched array elements do not conflict with local additions`() {
        val widget = LineItem(name = "Widget", quantity = 1)
        val widgetEnriched = LineItem(name = "Widget", quantity = 1, serverId = "li-1")
        val gadget = LineItem(name = "Gadget", quantity = 2)

        val base = testOrder(items = listOf(widget))
        val server = testOrder(
            serverId = "srv-1",
            version = "1",
            items = listOf(widgetEnriched),
        )
        val local = testOrder(items = listOf(widget, gadget))

        val result = orderMergeHandler.rebaseDataForPendingRequest(
            oldBaseData = base,
            currentData = local,
            newBaseData = server,
            pendingHttpRequest = makeOrderRequest(),
            pendingRequestId = 1,
            requestTag = "default",
        )

        assertIs<SyncableObjectRebaseHandler.RebaseResult.Rebased<TestOrder>>(result,
            "Server enriching existing elements while local only appends should not conflict")

        val merged = result.mergedData
        assertEquals(
            listOf(widgetEnriched, gadget),
            merged.items,
            "Merged items should have the server-enriched first item and the locally-added second item",
        )
    }

    /**
     * Reverse direction: server adds a new element while local modifies
     * an existing element. Should also merge without conflict.
     */
    @Test
    fun `local-modified array elements do not conflict with server additions`() {
        val widget = LineItem(name = "Widget", quantity = 1)
        val widgetUpdated = LineItem(name = "Widget", quantity = 5) // local changed quantity
        val gadget = LineItem(name = "Gadget", quantity = 2)

        val base = testOrder(items = listOf(widget))
        val server = testOrder(
            serverId = "srv-1",
            version = "1",
            items = listOf(widget, gadget), // server added gadget
        )
        val local = testOrder(items = listOf(widgetUpdated)) // local changed widget

        val result = orderMergeHandler.rebaseDataForPendingRequest(
            oldBaseData = base,
            currentData = local,
            newBaseData = server,
            pendingHttpRequest = makeOrderRequest(),
            pendingRequestId = 1,
            requestTag = "default",
        )

        assertIs<SyncableObjectRebaseHandler.RebaseResult.Rebased<TestOrder>>(result,
            "Server adding elements while local modifies existing should not conflict")

        val merged = result.mergedData
        assertEquals(
            listOf(widgetUpdated, gadget),
            merged.items,
            "Merged items should have the locally-modified first item and the server-added second item",
        )
    }

    /**
     * Both sides modified the same existing element — this IS a real conflict
     * and should still be reported.
     */
    @Test
    fun `both sides modifying same array element is still a conflict`() {
        val widget = LineItem(name = "Widget", quantity = 1)
        val widgetLocal = LineItem(name = "Widget Updated", quantity = 1)
        val widgetServer = LineItem(name = "Widget", quantity = 1, serverId = "li-1")

        val base = testOrder(items = listOf(widget))
        val server = testOrder(serverId = "srv-1", version = "1", items = listOf(widgetServer))
        val local = testOrder(items = listOf(widgetLocal))

        val result = orderMergeHandler.rebaseDataForPendingRequest(
            oldBaseData = base,
            currentData = local,
            newBaseData = server,
            pendingHttpRequest = makeOrderRequest(),
            pendingRequestId = 1,
            requestTag = "default",
        )

        assertIs<SyncableObjectRebaseHandler.RebaseResult.Conflict<TestOrder>>(result,
            "Both sides modifying the same element should still be a conflict")
    }

    // endregion
}
