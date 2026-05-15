package com.elvdev.buoyient.paging

import android.content.Context
import androidx.paging.PagingConfig as PagingConfig3
import androidx.paging.PagingSource.LoadParams
import androidx.paging.PagingSource.LoadResult
import androidx.paging.PagingState
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.elvdev.buoyient.BuoyientPlatformContext
import com.elvdev.buoyient.SyncableObject
import com.elvdev.buoyient.datatypes.Filter
import com.elvdev.buoyient.datatypes.PageCursor
import com.elvdev.buoyient.serviceconfigs.PagingConfig
import com.elvdev.buoyient.testing.MockResponse
import com.elvdev.buoyient.testing.TestServiceEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Unit tests for [BuoyientPagingSource]. Verifies the adapter's translation
 * between Paging 3's [LoadParams] and buoyient's [com.elvdev.buoyient.datatypes.PageDirection],
 * plus refresh key recovery and auto-invalidation.
 *
 * Pagination correctness *below* the adapter is tested in
 * `SyncableObjectServiceTest.loadPage*` — we don't re-test that here. These tests
 * focus on what the adapter itself adds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class BuoyientPagingSourceTest {

    // `Buoyient` reads `BuoyientPlatformContext.appContext` during service construction
    // (via `createPlatformSyncScheduleNotifier`), and `LocalStoreManager` triggers
    // `WorkManager.enqueue...` on every write. Robolectric gives us a real `Context`;
    // WorkManagerTestInitHelper keeps the enqueue calls from blowing up.
    //
    // Main dispatcher is needed only when autoRefreshOnLocalStoreChange is true, but
    // setting it for every test is cheap and lets any test toggle the flag.
    @BeforeTest
    fun setUp() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        BuoyientPlatformContext.initialize(appContext)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            appContext,
            Configuration.Builder().build(),
        )
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun makeService(
        sortOrder: PagingConfig.SortOrder = PagingConfig.SortOrder.ASC,
    ): Pair<TestItemService, TestServiceEnvironment> {
        val env = TestServiceEnvironment()
        env.connectivityChecker.online = false  // keep all writes local
        env.mockRouter.onGet("https://api.test.com/items") { _ ->
            MockResponse(200, buildJsonObject { put("data", buildJsonObject { }) })
        }
        val service = TestItemService(
            connectivityChecker = env.connectivityChecker,
            pagingConfig = PagingConfig(keyExtractor = { it.name }, sortOrder = sortOrder),
        )
        return service to env
    }

    private suspend fun seed(service: TestItemService, vararg names: String) {
        names.forEachIndexed { i, n ->
            service.testCreate(testItem(clientId = "c${i + 1}", name = n))
        }
    }

    // region Refresh translates to FromHead or Forward

    @Test
    fun `Refresh with null key loads from head`() = runBlocking {
        val (service, _) = makeService()
        seed(service, "Apple", "Banana", "Cherry")
        val source = BuoyientPagingSource(service)

        val result = source.load(LoadParams.Refresh(key = null, loadSize = 2, placeholdersEnabled = false))

        val page = assertIs<LoadResult.Page<PageCursor, TestItem>>(result)
        assertEquals(listOf("Apple", "Banana"), page.data.map { it.name })
        // FromHead → no prevKey (we're at the head).
        assertNull(page.prevKey)
        // Full page → nextKey advertises a forward boundary.
        assertNotNull(page.nextKey)
        service.close()
    }

    @Test
    fun `Refresh with non-null key loads forward from that cursor`() = runBlocking {
        val (service, _) = makeService()
        seed(service, "Apple", "Banana", "Cherry", "Date")
        val source = BuoyientPagingSource(service)

        // Refresh re-entering at a mid-list anchor (as getRefreshKey would produce).
        val midCursor = PageCursor(key = "Banana", clientId = "c2")
        val result = source.load(LoadParams.Refresh(key = midCursor, loadSize = 10, placeholdersEnabled = false))

        val page = assertIs<LoadResult.Page<PageCursor, TestItem>>(result)
        // Forward from Banana exclusive → Cherry, Date.
        assertEquals(listOf("Cherry", "Date"), page.data.map { it.name })
        service.close()
    }

    // endregion

    // region Append translates to Forward

    @Test
    fun `Append with cursor advances forward and exposes prev and next keys`() = runBlocking {
        val (service, _) = makeService()
        seed(service, "Apple", "Banana", "Cherry", "Date", "Elderberry")
        val source = BuoyientPagingSource(service)

        val firstPage = source.load(LoadParams.Refresh(key = null, loadSize = 2, placeholdersEnabled = false))
            as LoadResult.Page<PageCursor, TestItem>
        val firstNext = firstPage.nextKey!!

        val appended = source.load(LoadParams.Append(key = firstNext, loadSize = 2, placeholdersEnabled = false))

        val page = assertIs<LoadResult.Page<PageCursor, TestItem>>(appended)
        assertEquals(listOf("Cherry", "Date"), page.data.map { it.name })
        // Mid-list page has both boundaries available.
        assertNotNull(page.prevKey)
        assertNotNull(page.nextKey)
        // prevKey on a forward append is the first item of this page —
        // a backward load from it yields the previous page.
        assertEquals("Cherry", page.prevKey!!.key)
        service.close()
    }

    @Test
    fun `Append past the tail returns empty page with null nextKey`() = runBlocking {
        val (service, _) = makeService()
        seed(service, "Apple", "Banana")
        val source = BuoyientPagingSource(service)

        // Append from a cursor past the tail (Banana's cursor).
        val pastTail = PageCursor(key = "Banana", clientId = "c2")
        val result = source.load(LoadParams.Append(key = pastTail, loadSize = 10, placeholdersEnabled = false))

        val page = assertIs<LoadResult.Page<PageCursor, TestItem>>(result)
        assertTrue(page.data.isEmpty())
        assertNull(page.nextKey)
        assertNull(page.prevKey)
        service.close()
    }

    // endregion

    // region Prepend translates to Backward

    @Test
    fun `Prepend with cursor returns items before cursor in sort order`() = runBlocking {
        val (service, _) = makeService()
        seed(service, "Apple", "Banana", "Cherry", "Date")
        val source = BuoyientPagingSource(service)

        // Pretend the user is anchored mid-list at Cherry, then Paging 3 prepends.
        val cherryCursor = PageCursor(key = "Cherry", clientId = "c3")
        val result = source.load(LoadParams.Prepend(key = cherryCursor, loadSize = 10, placeholdersEnabled = false))

        val page = assertIs<LoadResult.Page<PageCursor, TestItem>>(result)
        // Items strictly before Cherry in ASC sort order — not reversed.
        assertEquals(listOf("Apple", "Banana"), page.data.map { it.name })
        // Hit head → prevKey null.
        assertNull(page.prevKey)
        // Still room forward (Cherry itself and beyond) → nextKey non-null.
        assertNotNull(page.nextKey)
        service.close()
    }

    @Test
    fun `Prepend returns prevKey when more pages exist before this one`() = runBlocking {
        val (service, _) = makeService()
        seed(service, "Apple", "Banana", "Cherry", "Date", "Elderberry")
        val source = BuoyientPagingSource(service)

        // Prepend with loadSize=2 from Elderberry → returns Cherry, Date (full page,
        // so prevKey != null because Apple, Banana still exist before).
        val end = PageCursor(key = "Elderberry", clientId = "c5")
        val result = source.load(LoadParams.Prepend(key = end, loadSize = 2, placeholdersEnabled = false))

        val page = assertIs<LoadResult.Page<PageCursor, TestItem>>(result)
        assertEquals(listOf("Cherry", "Date"), page.data.map { it.name })
        assertEquals("Cherry", page.prevKey!!.key)
        service.close()
    }

    // endregion

    // region getRefreshKey returns anchor page's prevKey

    @Test
    fun `getRefreshKey returns the cursor that originally loaded the anchor page`() = runBlocking {
        val (service, _) = makeService()
        seed(service, "Apple", "Banana", "Cherry", "Date")
        val source = BuoyientPagingSource(service)

        val firstPage = source.load(LoadParams.Refresh(key = null, loadSize = 2, placeholdersEnabled = false))
            as LoadResult.Page<PageCursor, TestItem>
        val secondPage = source.load(LoadParams.Append(key = firstPage.nextKey!!, loadSize = 2, placeholdersEnabled = false))
            as LoadResult.Page<PageCursor, TestItem>

        // User anchored at the second page (position 2 — Cherry).
        val state = PagingState(
            pages = listOf(firstPage, secondPage),
            anchorPosition = 2,
            config = PagingConfig3(pageSize = 2),
            leadingPlaceholderCount = 0,
        )

        val refreshKey = source.getRefreshKey(state)

        // The cursor that originally loaded secondPage is firstPage.nextKey
        // (Banana, the last item of the previous page). Refresh maps to
        // Forward(key), so this is what Forward-loads the same window.
        assertEquals(firstPage.nextKey, refreshKey)
        assertEquals("Banana", refreshKey!!.key)

        // Re-issuing Refresh with that key returns the same window the user was viewing.
        val refreshed = source.load(LoadParams.Refresh(key = refreshKey, loadSize = 2, placeholdersEnabled = false))
            as LoadResult.Page<PageCursor, TestItem>
        assertEquals(secondPage.data.map { it.name }, refreshed.data.map { it.name })
        service.close()
    }

    @Test
    fun `getRefreshKey returns null when state has no anchor`() {
        val (service, _) = makeService()
        val source = BuoyientPagingSource(service)
        val state = PagingState<PageCursor, TestItem>(
            pages = emptyList(),
            anchorPosition = null,
            config = PagingConfig3(pageSize = 2),
            leadingPlaceholderCount = 0,
        )
        assertNull(source.getRefreshKey(state))
        service.close()
    }

    // endregion

    // region autoRefreshOnLocalStoreChange

    @Test
    fun `autoRefreshOnLocalStoreChange invalidates on local store write`() = runBlocking {
        val (service, _) = makeService()
        seed(service, "Apple")
        val source = BuoyientPagingSource(service, autoRefreshOnLocalStoreChange = true)

        var invalidated = false
        source.registerInvalidatedCallback { invalidated = true }

        // A local write should tick service.localStoreChanges, which the source
        // observes via the Main-immediate scope set up in setUp().
        service.testCreate(testItem(clientId = "c2", name = "Banana"))

        assertTrue(invalidated, "expected source to be invalidated after a local store write")
        service.close()
    }

    @Test
    fun `autoRefreshOnLocalStoreChange disabled means no invalidation on local write`() = runBlocking {
        val (service, _) = makeService()
        seed(service, "Apple")
        val source = BuoyientPagingSource(service, autoRefreshOnLocalStoreChange = false)

        var invalidated = false
        source.registerInvalidatedCallback { invalidated = true }

        service.testCreate(testItem(clientId = "c2", name = "Banana"))

        assertEquals(false, invalidated)
        service.close()
    }

    // endregion

    // region filter and syncStatus pass through

    @Test
    fun `filter passed at construction restricts each loaded page`() = runBlocking {
        val (service, _) = makeService()
        service.testCreate(testItem(clientId = "c1", name = "Apple", value = 1))
        service.testCreate(testItem(clientId = "c2", name = "Banana", value = 2))
        service.testCreate(testItem(clientId = "c3", name = "Cherry", value = 1))
        val source = BuoyientPagingSource(service, filter = Filter.eq("$.value", 1))

        val result = source.load(LoadParams.Refresh(key = null, loadSize = 10, placeholdersEnabled = false))
            as LoadResult.Page<PageCursor, TestItem>

        // Banana (value=2) excluded by filter; Apple and Cherry remain.
        assertEquals(listOf("Apple", "Cherry"), result.data.map { it.name })
        service.close()
    }

    @Test
    fun `syncStatus passed at construction restricts each loaded page`() = runBlocking {
        val (service, _) = makeService()
        // All three items are PENDING_CREATE (offline writes); they all match.
        seed(service, "Apple", "Banana", "Cherry")
        val source = BuoyientPagingSource(
            service,
            syncStatus = SyncableObject.SyncStatus.PENDING_CREATE,
        )

        val result = source.load(LoadParams.Refresh(key = null, loadSize = 10, placeholdersEnabled = false))
            as LoadResult.Page<PageCursor, TestItem>

        assertEquals(3, result.data.size)
        service.close()
    }

    // endregion

    // region error mapping

    @Test
    fun `loadPage throwing turns into LoadResult Error`(): Unit = runBlocking {
        // Construct a service with a malformed indexedJsonPaths. The filter-query path
        // validates each path on first use and throws IllegalArgumentException, which
        // BuoyientPagingSource must catch and convert to LoadResult.Error.
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

        val source = BuoyientPagingSource(service, filter = Filter.eq("$.value", 1))
        val result = try {
            source.load(LoadParams.Refresh(key = null, loadSize = 10, placeholdersEnabled = false))
        } catch (_: Throwable) {
            fail("source.load should catch exceptions and return LoadResult.Error, not throw")
        }
        assertIs<LoadResult.Error<PageCursor, TestItem>>(result)
        service.close()
    }

    // endregion
}
