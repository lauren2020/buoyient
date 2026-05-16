package com.elvdev.buoyient.paging

import com.elvdev.buoyient.datatypes.Filter
import com.elvdev.buoyient.datatypes.PageCursor
import com.elvdev.buoyient.serviceconfigs.PagingConfig
import com.elvdev.buoyient.testing.MockResponse
import com.elvdev.buoyient.testing.TestServiceEnvironment
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [BuoyientPagedList] — the iOS-friendly paging coordinator. The class
 * lives in commonMain and is iOS-compatible; this test runs on the JVM target.
 *
 * Coverage focuses on the state-machine behavior — items / cursors / loadState /
 * has-more flags moving in lockstep across refresh, loadMore, loadPrevious. The
 * pagination correctness underneath (cursor predicates, filters, sort order) is
 * already verified in `SyncableObjectServiceTest.loadPage*`.
 */
class BuoyientPagedListTest {

    private fun makeService(
        sortOrder: PagingConfig.SortOrder = PagingConfig.SortOrder.ASC,
    ): Pair<TestItemService, TestServiceEnvironment> {
        val env = TestServiceEnvironment()
        env.connectivityChecker.online = false
        env.mockRouter.onGet("https://api.test.com/items") { _ ->
            MockResponse(200, buildJsonObject { put("data", buildJsonObject { }) })
        }
        val service = TestItemService(
            connectivityChecker = env.connectivityChecker,
            pagingConfig = PagingConfig(keyExtractor = { it.name }, sortOrder = sortOrder),
        )
        return service to env
    }

    private suspend fun seedNumbered(service: TestItemService, count: Int) {
        for (i in 0 until count) {
            val padded = i.toString().padStart(3, '0')
            service.testCreate(testItem(clientId = "c$padded", name = "Item-$padded"))
        }
    }

    // region refresh

    @Test
    fun refresh_loads_first_page_from_head_and_sets_state() = runBlocking {
        val (service, _) = makeService()
        seedNumbered(service, count = 25)
        val list = BuoyientPagedList(service, pageSize = 10)

        list.refresh()

        assertEquals(listOf("Item-000", "Item-001", "Item-002", "Item-003", "Item-004",
            "Item-005", "Item-006", "Item-007", "Item-008", "Item-009"),
            list.items.value.map { it.name })
        assertTrue(list.hasMoreForward.value, "forward should still have more pages")
        assertFalse(list.hasMoreBackward.value, "FromHead → nothing before us")
        assertIs<BuoyientPagedList.LoadState.Idle>(list.loadState.value)
        list.close()
        service.close()
    }

    @Test
    fun refresh_resets_items_when_called_after_loadMore() = runBlocking {
        val (service, _) = makeService()
        seedNumbered(service, count = 25)
        val list = BuoyientPagedList(service, pageSize = 5)

        list.refresh()
        list.loadMore()
        assertEquals(10, list.items.value.size)

        list.refresh()
        assertEquals(5, list.items.value.size)
        assertEquals("Item-000", list.items.value.first().name)
        list.close()
        service.close()
    }

    @Test
    fun refresh_marks_hasMoreForward_false_when_dataset_fits_in_one_page() = runBlocking {
        val (service, _) = makeService()
        seedNumbered(service, count = 3)
        val list = BuoyientPagedList(service, pageSize = 10)

        list.refresh()

        assertEquals(3, list.items.value.size)
        assertFalse(list.hasMoreForward.value, "loaded all rows in one page → no more forward")
        list.close()
        service.close()
    }

    // endregion

    // region loadMore (Forward append)

    @Test
    fun loadMore_appends_next_forward_page() = runBlocking {
        val (service, _) = makeService()
        seedNumbered(service, count = 25)
        val list = BuoyientPagedList(service, pageSize = 10)

        list.refresh()
        list.loadMore()

        assertEquals(20, list.items.value.size)
        assertEquals("Item-000", list.items.value.first().name)
        assertEquals("Item-019", list.items.value.last().name)
        assertTrue(list.hasMoreForward.value, "still 5 more rows to load")
        list.close()
        service.close()
    }

    @Test
    fun loadMore_stops_when_tail_reached() = runBlocking {
        val (service, _) = makeService()
        seedNumbered(service, count = 25)
        val list = BuoyientPagedList(service, pageSize = 10)

        list.refresh()
        list.loadMore()  // 20 total
        list.loadMore()  // 25 total — short page, hits tail

        assertEquals(25, list.items.value.size)
        assertFalse(list.hasMoreForward.value)

        // A further loadMore is a no-op.
        list.loadMore()
        assertEquals(25, list.items.value.size)
        list.close()
        service.close()
    }

    // endregion

    // region initialKey + loadPrevious (Backward prepend)

    @Test
    fun initialKey_starts_forward_from_cursor_and_hasMoreBackward_is_true() = runBlocking {
        val (service, _) = makeService()
        seedNumbered(service, count = 25)
        val list = BuoyientPagedList(
            service = service,
            pageSize = 5,
            initialKey = PageCursor(key = "Item-009", clientId = "c009"),
        )

        list.refresh()

        // Forward(Item-009) returns Item-010..Item-014.
        assertEquals(listOf("Item-010", "Item-011", "Item-012", "Item-013", "Item-014"),
            list.items.value.map { it.name })
        assertTrue(list.hasMoreBackward.value, "items exist before Item-010")
        assertTrue(list.hasMoreForward.value)
        list.close()
        service.close()
    }

    @Test
    fun loadPrevious_prepends_items_in_sort_order() = runBlocking {
        val (service, _) = makeService()
        seedNumbered(service, count = 25)
        val list = BuoyientPagedList(
            service = service,
            pageSize = 5,
            initialKey = PageCursor(key = "Item-009", clientId = "c009"),
        )

        list.refresh()  // Item-010..Item-014
        list.loadPrevious()  // should prepend Item-005..Item-009

        assertEquals(
            listOf("Item-005", "Item-006", "Item-007", "Item-008", "Item-009",
                "Item-010", "Item-011", "Item-012", "Item-013", "Item-014"),
            list.items.value.map { it.name },
        )
        assertTrue(list.hasMoreBackward.value, "Item-000..Item-004 still before us")
        list.close()
        service.close()
    }

    @Test
    fun loadPrevious_stops_when_head_reached() = runBlocking {
        val (service, _) = makeService()
        seedNumbered(service, count = 25)
        val list = BuoyientPagedList(
            service = service,
            pageSize = 5,
            initialKey = PageCursor(key = "Item-009", clientId = "c009"),
        )

        list.refresh()  // Item-010..Item-014
        list.loadPrevious()  // Item-005..Item-014 (10 total)
        list.loadPrevious()  // Item-000..Item-014 (15 total) — exactly hits the head

        assertEquals(15, list.items.value.size)
        assertEquals("Item-000", list.items.value.first().name)
        // The previous page returned a full 5 items, so the engine doesn't yet
        // *know* there's nothing before — it'll find out on the next attempt.
        assertTrue(list.hasMoreBackward.value, "engine doesn't know head was reached until next probe")

        // One more probe — Backward(Item-000) returns zero items, marks the head.
        list.loadPrevious()
        assertEquals(15, list.items.value.size)
        assertFalse(list.hasMoreBackward.value, "head confirmed after empty backward page")

        // Subsequent calls are no-ops.
        list.loadPrevious()
        assertEquals(15, list.items.value.size)
        list.close()
        service.close()
    }

    @Test
    fun loadPrevious_is_noop_when_started_FromHead() = runBlocking {
        val (service, _) = makeService()
        seedNumbered(service, count = 5)
        val list = BuoyientPagedList(service, pageSize = 10)

        list.refresh()
        assertFalse(list.hasMoreBackward.value)
        list.loadPrevious()

        // Items unchanged, no error state.
        assertEquals(5, list.items.value.size)
        assertIs<BuoyientPagedList.LoadState.Idle>(list.loadState.value)
        list.close()
        service.close()
    }

    // endregion

    // region autoRefreshOnLocalStoreChange

    @Test
    fun autoRefreshOnLocalStoreChange_refreshes_when_local_store_writes() = runBlocking {
        val (service, _) = makeService()
        seedNumbered(service, count = 3)
        val list = BuoyientPagedList(
            service = service,
            pageSize = 10,
            autoRefreshOnLocalStoreChange = true,
        )
        list.refresh()
        assertEquals(3, list.items.value.size)

        // The localStoreChanges subscription runs on Dispatchers.Default. launchIn
        // schedules the collection but doesn't guarantee it has attached as a
        // subscriber by the time we return — and SharedFlow with replay=0 drops
        // any emission that fires before there's a subscriber. A brief yield lets
        // Default pick up the launch before we trigger the write. In production
        // this race is invisible because real users don't write within microseconds
        // of constructing the list.
        kotlinx.coroutines.delay(100)
        service.testCreate(testItem(clientId = "c099", name = "Item-099"))

        waitUntil { list.items.value.any { it.name == "Item-099" } }
        assertEquals(4, list.items.value.size)
        list.close()
        service.close()
    }

    @Test
    fun autoRefreshOnLocalStoreChange_disabled_does_not_refresh() = runBlocking {
        val (service, _) = makeService()
        seedNumbered(service, count = 3)
        val list = BuoyientPagedList(service, pageSize = 10, autoRefreshOnLocalStoreChange = false)
        list.refresh()
        val beforeCount = list.items.value.size

        service.testCreate(testItem(clientId = "c099", name = "Item-099"))

        // No background refresh; list still reflects the snapshot from the explicit refresh().
        assertEquals(beforeCount, list.items.value.size)
        list.close()
        service.close()
    }

    // endregion

    // region close

    @Test
    fun close_makes_subsequent_calls_noop() = runBlocking {
        val (service, _) = makeService()
        seedNumbered(service, count = 5)
        val list = BuoyientPagedList(service, pageSize = 10)
        list.refresh()
        assertEquals(5, list.items.value.size)

        list.close()
        list.refresh()  // should silently no-op, not throw
        list.loadMore()
        list.loadPrevious()

        assertEquals(5, list.items.value.size)
        service.close()
    }

    // endregion

    // region sortOrder override

    @Test
    fun sortOrder_override_flips_direction_for_this_list() = runBlocking {
        // makeService() uses ASC by default. Override to DESC for this list only.
        val (service, _) = makeService(sortOrder = PagingConfig.SortOrder.ASC)
        seedNumbered(service, count = 3)
        val list = BuoyientPagedList(
            service = service,
            pageSize = 10,
            sortOrder = PagingConfig.SortOrder.DESC,
        )

        list.refresh()

        // DESC override: Item-002, Item-001, Item-000.
        assertEquals(listOf("Item-002", "Item-001", "Item-000"), list.items.value.map { it.name })
        list.close()
        service.close()
    }

    @Test
    fun sortOrder_null_falls_back_to_service_default() = runBlocking {
        val (service, _) = makeService(sortOrder = PagingConfig.SortOrder.ASC)
        seedNumbered(service, count = 3)
        // No sortOrder override — uses service's ASC.
        val list = BuoyientPagedList(service = service, pageSize = 10)

        list.refresh()

        assertEquals(listOf("Item-000", "Item-001", "Item-002"), list.items.value.map { it.name })
        list.close()
        service.close()
    }

    // endregion

    // region filter and syncStatus

    @Test
    fun filter_restricts_pages() = runBlocking {
        val (service, _) = makeService()
        service.testCreate(testItem(clientId = "c1", name = "Apple", value = 1))
        service.testCreate(testItem(clientId = "c2", name = "Banana", value = 2))
        service.testCreate(testItem(clientId = "c3", name = "Cherry", value = 1))
        val list = BuoyientPagedList(service, pageSize = 10, filter = Filter.eq("$.value", 1))

        list.refresh()

        assertEquals(listOf("Apple", "Cherry"), list.items.value.map { it.name })
        list.close()
        service.close()
    }

    // endregion

    // region error state

    @Test
    fun loadPage_throwing_surfaces_as_LoadState_Error() = runBlocking {
        // Malformed indexedJsonPath → ensureIndexedPaths throws on the first
        // filter query, which BuoyientPagedList must catch and expose via loadState.
        val env = TestServiceEnvironment()
        env.connectivityChecker.online = false
        env.mockRouter.onGet("https://api.test.com/items") { _ ->
            MockResponse(200, buildJsonObject { put("data", buildJsonObject { }) })
        }
        val service = TestItemService(
            connectivityChecker = env.connectivityChecker,
            indexedJsonPaths = listOf("not-a-valid-path"),
        )
        service.testCreate(testItem(clientId = "c1", name = "Apple"))
        val list = BuoyientPagedList(service, pageSize = 10, filter = Filter.eq("$.value", 1))

        list.refresh()

        val state = list.loadState.value
        assertIs<BuoyientPagedList.LoadState.Error>(state)
        assertNotNull(state.throwable)
        list.close()
        service.close()
    }

    // endregion

    /**
     * Spin-wait until [condition] is true or [timeoutMillis] elapses. Used for
     * assertions that depend on a background coroutine (e.g. the auto-refresh
     * subscription) flushing — `runBlocking` won't yield cooperatively otherwise.
     */
    private suspend fun waitUntil(timeoutMillis: Long = 2_000, condition: () -> Boolean) {
        val start = TimeSource.Monotonic.markNow()
        val timeout = timeoutMillis.milliseconds
        while (!condition() && start.elapsedNow() < timeout) {
            kotlinx.coroutines.delay(10)
        }
        assertTrue(condition(), "timed out waiting for condition after ${timeoutMillis}ms")
    }
}
