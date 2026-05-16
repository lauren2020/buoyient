package com.elvdev.buoyient.paging

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.paging.Pager
import androidx.paging.PagingConfig as PagingConfig3
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.elvdev.buoyient.BuoyientPlatformContext
import com.elvdev.buoyient.datatypes.PageCursor
import com.elvdev.buoyient.serviceconfigs.PagingConfig
import com.elvdev.buoyient.testing.MockResponse
import com.elvdev.buoyient.testing.TestServiceEnvironment
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * UI tests for a sample [LazyColumn] backed by [BuoyientPagingSource] via
 * [androidx.paging.compose.collectAsLazyPagingItems]. Verifies the three flows that
 * matter to consumers: initial load + scroll-down (append), starting mid-list +
 * scroll-up (prepend), and reactive recomposition when the local store changes.
 *
 * The pagination correctness *underneath* the adapter is tested in
 * [BuoyientPagingSourceTest] and `SyncableObjectServiceTest.loadPage*`. These tests
 * focus on the Compose wiring — that `prevKey`/`nextKey` plumbing actually drives
 * the list as items scroll past prefetch boundaries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class BuoyientPagingComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @BeforeTest
    fun setUp() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        BuoyientPlatformContext.initialize(appContext)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            appContext,
            Configuration.Builder().build(),
        )
        // No Dispatchers.setMain here — Compose's UI test rule owns the Main
        // dispatcher under Robolectric, and overriding it breaks recomposition.
    }

    /**
     * A minimal sample composable consumers can mirror. Renders one row per item;
     * `LazyPagingItems` handles prefetch/append/prepend driven by [BuoyientPagingSource].
     */
    @Composable
    private fun PagingItemList(
        pager: Pager<PageCursor, TestItem>,
        listState: LazyListState = rememberLazyListState(),
    ) {
        val items = pager.flow.collectAsLazyPagingItems()
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag("item-list"),
        ) {
            items(
                count = items.itemCount,
                key = items.itemKey { it.clientId },
            ) { index ->
                val item = items[index]
                BasicText(text = item?.name ?: "…")
            }
        }
    }

    private fun makeService(): Pair<TestItemService, TestServiceEnvironment> {
        val env = TestServiceEnvironment()
        env.connectivityChecker.online = false  // keep all writes local
        env.mockRouter.onGet("https://api.test.com/items") { _ ->
            MockResponse(200, buildJsonObject { put("data", buildJsonObject { }) })
        }
        val service = TestItemService(
            connectivityChecker = env.connectivityChecker,
            pagingConfig = PagingConfig(
                keyExtractor = { it.name },
                sortOrder = PagingConfig.SortOrder.ASC,
            ),
        )
        return service to env
    }

    private suspend fun seedNumbered(service: TestItemService, count: Int) {
        // Zero-pad so paging key (= name) sorts the same way as the numeric index.
        for (i in 0 until count) {
            val padded = i.toString().padStart(3, '0')
            service.testCreate(testItem(clientId = "c$padded", name = "Item-$padded"))
        }
    }

    private fun waitUntilTextAppears(text: String, timeoutMillis: Long = 5_000) {
        composeTestRule.waitUntil(timeoutMillis) {
            composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    // region Append: scroll down loads more pages

    @Test
    fun `initial load shows head, scrolling triggers append for later items`() = runBlocking {
        val (service, _) = makeService()
        seedNumbered(service, count = 50)

        val pager = Pager(
            config = PagingConfig3(pageSize = 10, initialLoadSize = 10, prefetchDistance = 2),
            pagingSourceFactory = { BuoyientPagingSource(service) },
        )

        composeTestRule.setContent { PagingItemList(pager) }

        // Initial page loads from the head.
        waitUntilTextAppears("Item-000")
        composeTestRule.onNodeWithText("Item-000").assertIsDisplayed()
        // A row near the end of the data set hasn't been paged in yet.
        composeTestRule.onAllNodesWithText("Item-040").fetchSemanticsNodes().also {
            assert(it.isEmpty()) { "Item-040 should not be loaded before scrolling" }
        }

        // Scroll deep enough that Paging 3 issues Append loads.
        composeTestRule.onNodeWithTag("item-list").performScrollToNode(hasText("Item-040"))

        waitUntilTextAppears("Item-040")
        composeTestRule.onNodeWithText("Item-040").assertIsDisplayed()
        service.close()
    }

    // endregion

    // region Prepend: starting mid-list and scrolling up

    @Test
    fun `initialKey starts mid-list, scrolling up triggers prepend for earlier items`() = runBlocking {
        val (service, _) = makeService()
        seedNumbered(service, count = 50)

        // Start the pager at Item-024's cursor. With sort key = name, Forward from
        // "Item-024" returns Item-025, Item-026, ... — Paging 3 then issues a Prepend
        // to fetch the items before that window when the user scrolls toward index 0.
        val initialKey = PageCursor(key = "Item-024", clientId = "c024")
        val pager = Pager(
            config = PagingConfig3(pageSize = 10, initialLoadSize = 10, prefetchDistance = 3),
            initialKey = initialKey,
            pagingSourceFactory = { BuoyientPagingSource(service) },
        )

        composeTestRule.setContent { PagingItemList(pager) }

        // Initial mid-list window appears.
        waitUntilTextAppears("Item-025")
        composeTestRule.onNodeWithText("Item-025").assertIsDisplayed()
        // The head of the list hasn't been paged in yet — Item-014 is what the first
        // Prepend load will bring in (Backward(Item-025), pageSize 10 → Item-015..Item-024).
        composeTestRule.onAllNodesWithText("Item-014").fetchSemanticsNodes().also {
            assert(it.isEmpty()) { "Item-014 should not be loaded before scrolling up" }
        }

        // Scrolling to index 0 issues an accessHint at the leading edge, which —
        // combined with prevKey != null — causes Paging 3 to enqueue a Prepend.
        // `performScrollToNode` alone doesn't reliably trip Prepend because it
        // scrolls within the currently-loaded range.
        composeTestRule.onNodeWithTag("item-list").performScrollToIndex(0)

        // First Prepend brings in Item-015..Item-024.
        waitUntilTextAppears("Item-015")
        composeTestRule.onNodeWithText("Item-015").assertIsDisplayed()

        // Now we can scroll-by-content to a target the second Prepend will bring in.
        composeTestRule.onNodeWithTag("item-list").performScrollToNode(hasText("Item-015"))
        composeTestRule.onNodeWithTag("item-list").performScrollToIndex(0)
        waitUntilTextAppears("Item-005")
        composeTestRule.onNodeWithText("Item-005").assertIsDisplayed()
        service.close()
    }

    // endregion

    // region autoRefreshOnLocalStoreChange propagates writes to the UI

    @Test
    fun `local write while rendered updates the list when autoRefresh is enabled`() = runBlocking {
        val (service, _) = makeService()
        seedNumbered(service, count = 3)

        val pager = Pager(
            config = PagingConfig3(pageSize = 10, initialLoadSize = 10),
            pagingSourceFactory = {
                BuoyientPagingSource(service, autoRefreshOnLocalStoreChange = true)
            },
        )

        composeTestRule.setContent { PagingItemList(pager) }

        waitUntilTextAppears("Item-000")
        // New item isn't there yet.
        composeTestRule.onAllNodesWithText("Item-099").fetchSemanticsNodes().also {
            assert(it.isEmpty()) { "Item-099 should not exist before we add it" }
        }

        // Write a new item — autoRefreshOnLocalStoreChange should invalidate the
        // PagingSource, Paging 3 reloads, and the list re-renders with the new row.
        service.testCreate(testItem(clientId = "c099", name = "Item-099"))

        waitUntilTextAppears("Item-099")
        composeTestRule.onNodeWithText("Item-099").assertIsDisplayed()
        service.close()
    }

    // endregion
}
