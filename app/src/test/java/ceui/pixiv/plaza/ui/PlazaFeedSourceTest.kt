package ceui.pixiv.plaza.ui

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import ceui.pixiv.feeds.FeedViewModel
import ceui.pixiv.feeds.LoadState
import ceui.pixiv.feeds.cache.CachedFirstPage
import ceui.pixiv.feeds.cache.FeedFirstPageStore
import ceui.pixiv.plaza.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The feed list on the feeds framework must keep the list-mode guarantees the retired
 * timeline ViewModel had: disk first page before network, account and revision fences,
 * snapshot persistence rules, deferred refresh during a mutation, and the resume policy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlazaFeedSourceTest {
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = ViewModelStore()
    private val cacheWrites = CoroutineScope(SupervisorJob() + dispatcher)
    private var key = 0

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        viewModels.clear()
        cacheWrites.cancel()
        Dispatchers.resetMain()
    }

    private fun post(id: Long, liked: Boolean = false) =
        PlazaPost(id, 42, "Alice", "hello", 1, null, null, null, if (liked) 1 else 0, 0, liked, emptyList())

    private fun source(
        api: FakeApi,
        mine: () -> Boolean = { false },
        cache: (Boolean, Long) -> FeedFirstPageStore<PlazaPage>? = { _, _ -> null },
        currentUid: () -> Long = { 42L },
        onFirstPage: (Long) -> Unit = {},
    ) = PlazaFeedSource(api, currentUid, mine, cache, cacheWrites, onFirstPage)

    private fun feed(source: PlazaFeedSource) =
        FeedViewModel(source, autoLoad = false, cursorClass = Long::class.java).also {
            viewModels.put("feed-${key++}", it)
        }

    private fun controller(api: FakeApi, currentUid: () -> Long = { 42L }) =
        PlazaFeedController(SavedStateHandle(), api, currentUid, { _, _ -> null }, cacheWrites).also {
            viewModels.put("controller-${key++}", it)
        }

    private fun FeedViewModel<*>.posts() = uiState.value.items.map { (it as PlazaPostItem).post }

    @Test
    fun `disk first page is visible before network and survives offline refresh`() = runTest(dispatcher) {
        val cached = post(6001)
        val store = FakeStore(PlazaPage(listOf(cached), 6001))
        val network = CompletableDeferred<PlazaPage>()
        val api = FakeApi().apply { pages.add(network) }
        val vm = feed(source(api, cache = { _, _ -> store }))
        vm.refresh()
        runCurrent()
        assertSame(cached, vm.posts().single())
        assertTrue(vm.uiState.value.refresh is LoadState.Loading)
        assertTrue(vm.uiState.value.itemsFromCache)
        assertSame(cached, PlazaRepository.cachedEntry(cached.id, 42)?.post)
        assertFalse(PlazaRepository.hasFreshPost(cached.id, 42))
        network.completeExceptionally(IOException("offline"))
        runCurrent()
        assertSame(cached, vm.posts().single())
        assertTrue(vm.uiState.value.refresh is LoadState.Error)
        assertEquals(0, store.writes)
    }

    @Test
    fun `only network first pages replace disk snapshot and refresh never rolls back to disk`() = runTest(dispatcher) {
        val store = FakeStore(PlazaPage(listOf(post(6004)), 6004))
        val first = PlazaPage(listOf(post(6005)), 6005)
        val api = FakeApi().apply { pages.add(CompletableDeferred(first)) }
        val vm = feed(source(api, cache = { _, _ -> store }))
        vm.refresh()
        runCurrent()
        assertEquals(first, store.snapshot!!.payload)
        assertEquals("6005", store.snapshot!!.nextCursor)
        api.pages.add(CompletableDeferred(PlazaPage(listOf(post(6006)), null)))
        vm.loadMore()
        runCurrent()
        assertEquals(listOf(6005L, 6006L), vm.posts().map { it.id })
        assertEquals(1, store.writes)
        assertEquals(first, store.snapshot!!.payload)
        api.pages.add(CompletableDeferred())
        vm.refresh()
        runCurrent()
        assertEquals(listOf(6005L, 6006L), vm.posts().map { it.id })
        assertEquals(1, store.reads)
    }

    @Test
    fun `empty network first page is persisted so a stale snapshot cannot return`() = runTest(dispatcher) {
        val store = FakeStore(PlazaPage(listOf(post(6007)), null))
        val api = FakeApi().apply { pages.add(CompletableDeferred(PlazaPage(emptyList(), null))) }
        val vm = feed(source(api, cache = { _, _ -> store }))
        vm.refresh()
        runCurrent()
        assertTrue(vm.uiState.value.showEmptyState)
        assertEquals(1, store.writes)
        assertTrue(store.snapshot!!.payload.items.isEmpty())
    }

    @Test
    fun `unreadable and structurally broken snapshots fall back to network`() = runTest(dispatcher) {
        for (brokenShape in listOf(false, true)) {
            val page =
                if (brokenShape) com.google.gson.Gson().fromJson("{}", PlazaPage::class.java)
                else PlazaPage(emptyList(), null)
            val store = FakeStore(page).apply { if (!brokenShape) readFailure = IOException("disk") }
            val fresh = post(if (brokenShape) 6011 else 6012)
            val api = FakeApi().apply { pages.add(CompletableDeferred(PlazaPage(listOf(fresh), null))) }
            val vm = feed(source(api, cache = { _, _ -> store }))
            vm.refresh()
            runCurrent()
            assertSame(fresh, vm.posts().single())
            assertTrue(vm.uiState.value.refresh is LoadState.Idle)
            assertFalse(vm.uiState.value.itemsFromCache)
            assertEquals(1, store.writes)
        }
    }

    @Test
    fun `late cache read and late responses cannot cross account boundaries`() = runTest(dispatcher) {
        var uid = 99L
        val delayed = FakeStore(PlazaPage(listOf(post(6010)), null)).apply { readGate = CompletableDeferred() }
        val api = FakeApi().apply { pages.add(CompletableDeferred()) }
        val vm = feed(source(api, cache = { _, _ -> delayed }, currentUid = { uid }))
        vm.refresh()
        runCurrent()
        uid = 100
        delayed.readGate!!.complete(Unit)
        runCurrent()
        assertTrue(vm.posts().isEmpty())
        assertNull(PlazaRepository.cachedEntry(6010, 99))
        assertNull(PlazaRepository.cachedEntry(6010, 100))
        // The network stage pins the account it started with (now 100) and keeps loading.
        assertTrue(vm.uiState.value.refresh is LoadState.Loading)

        val late = CompletableDeferred<PlazaPage>()
        uid = 101
        val second = feed(source(FakeApi().apply { pages.add(late) }, currentUid = { uid }))
        second.refresh()
        runCurrent()
        uid = 102
        late.complete(PlazaPage(listOf(post(6013)), null))
        runCurrent()
        assertTrue(second.posts().isEmpty())
        assertNull(PlazaRepository.cachedEntry(6013, 101))
        assertNull(PlazaRepository.cachedEntry(6013, 102))
    }

    @Test
    fun `mine scope requests the account's posts and reads its own snapshot`() = runTest(dispatcher) {
        val keys = mutableListOf<Pair<Boolean, Long>>()
        val mine = FakeStore(PlazaPage(listOf(post(6020)), null))
        val api = FakeApi().apply { pages.add(CompletableDeferred(PlazaPage(listOf(post(6021)), null))) }
        val vm = feed(source(api, mine = { true }, cache = { own, account -> keys += own to account; mine }))
        vm.refresh()
        runCurrent()
        assertEquals(listOf(42L), api.authors)
        assertEquals(listOf(6021L), vm.posts().map { it.id })
        assertEquals(listOf(true to 42L, true to 42L), keys)
        assertEquals(1, mine.reads)
        assertEquals(1, mine.writes)
    }

    @Test
    fun `a page overlapping a mutation is fetched again instead of overwriting newer state`() = runTest(dispatcher) {
        val original = post(6030)
        val stale = CompletableDeferred<PlazaPage>()
        val api = FakeApi().apply { pages.add(stale) }
        val vm = feed(source(api))
        vm.refresh()
        runCurrent()
        // Another screen liked the post while the list page was in flight.
        PlazaRepository.changed()
        PlazaRepository.cache(original.copy(liked = true, likeCount = 1), 42)
        api.pages.add(CompletableDeferred(PlazaPage(listOf(post(6030, liked = true)), null)))
        stale.complete(PlazaPage(listOf(original), null))
        runCurrent()
        assertTrue(vm.posts().single().liked)
        assertTrue(PlazaRepository.cachedEntry(6030, 42)!!.post!!.liked)
        assertEquals(2, api.feedCalls)
    }

    @Test
    fun `first page records the revision it was loaded at`() = runTest(dispatcher) {
        val seen = mutableListOf<Long>()
        val api = FakeApi().apply { pages.add(CompletableDeferred(PlazaPage(listOf(post(6040)), null))) }
        val vm = feed(source(api, onFirstPage = { seen += it }))
        vm.refresh()
        runCurrent()
        assertEquals(listOf(PlazaRepository.revision.value), seen)
    }

    @Test
    fun `failed like leaves the pool unchanged clears busy and alerts once`() = runTest(dispatcher) {
        val target = post(6050)
        PlazaRepository.cache(target, 42)
        val api = FakeApi().apply { likeFails = true }
        val c = controller(api)
        c.like(target)
        assertEquals(setOf(6050L), c.busyIds.value)
        runCurrent()
        assertTrue(c.busyIds.value.isEmpty())
        assertFalse(PlazaRepository.cachedEntry(6050, 42)!!.post!!.liked)
        assertNotNull(c.takeErrorForAlert())
        assertNull(c.takeErrorForAlert())
    }

    @Test
    fun `refresh during mutation waits and duplicate taps make one request`() = runTest(dispatcher) {
        val target = post(6060)
        val api = FakeApi().apply { likeGate = CompletableDeferred() }
        val c = controller(api)
        val replayed = mutableListOf<Unit>()
        val collector = launch { c.refreshRequests.collect { replayed += it } }
        runCurrent()
        c.like(target)
        c.like(target)
        runCurrent()
        assertFalse(c.requestRefresh())
        assertTrue(replayed.isEmpty())
        api.likeGate!!.complete(post(6060, liked = true))
        runCurrent()
        assertEquals(1, api.likeCalls)
        assertEquals(1, replayed.size)
        assertTrue(c.busyIds.value.isEmpty())
        assertTrue(PlazaRepository.cachedEntry(6060, 42)!!.post!!.liked)
        assertTrue(c.requestRefresh())
        collector.cancel()
    }

    @Test
    fun `deleting a post tombstones the shared entry and bumps the revision`() = runTest(dispatcher) {
        val target = post(6070)
        PlazaRepository.cache(target, 42)
        val before = PlazaRepository.revision.value
        val c = controller(FakeApi())
        c.delete(target)
        runCurrent()
        assertNull(PlazaRepository.cachedEntry(6070, 42)!!.post)
        assertTrue(PlazaRepository.revision.value > before)
        assertNull(c.takeErrorForAlert())
    }

    @Test
    fun `resume policy refreshes on foreign revisions and restarts on account change`() {
        var uid = 42L
        val c = controller(FakeApi()) { uid }
        assertEquals(PlazaFeedController.Entry.NONE, c.enter())
        assertEquals(42L, c.account)
        assertEquals(PlazaFeedController.Entry.NONE, c.enter())
        PlazaRepository.changed()
        assertEquals(PlazaFeedController.Entry.REFRESH, c.enter())
        assertTrue(c.requestRefresh())
        assertEquals(PlazaFeedController.Entry.NONE, c.enter())
        uid = 99
        assertEquals(PlazaFeedController.Entry.SWITCH_SCOPE, c.enter())
        assertEquals(99L, c.account)
        assertFalse(c.selectMine(false))
        assertTrue(c.selectMine(true))
        assertTrue(c.mine)
    }

    @Test
    fun `a scope restart records its own first page so the next resume stays put`() = runTest(dispatcher) {
        val api = FakeApi().apply { pages.add(CompletableDeferred(PlazaPage(listOf(post(6080)), null))) }
        val c = controller(api)
        assertEquals(PlazaFeedController.Entry.NONE, c.enter())
        assertTrue(c.selectMine(true))
        // The page restarts the list itself instead of going through requestRefresh; a first
        // page that lands is what records the revision and the load time.
        assertEquals(PlazaFeedController.Entry.REFRESH, c.enter())
        val vm = feed(c.source)
        vm.refresh()
        runCurrent()
        assertEquals(listOf(42L), api.authors)
        assertEquals(PlazaFeedController.Entry.NONE, c.enter())
    }

    private class FakeStore(page: PlazaPage) : FeedFirstPageStore<PlazaPage> {
        var snapshot: CachedFirstPage<PlazaPage>? =
            CachedFirstPage(page, page.nextBefore?.toString(), 100)
        var reads = 0
        var writes = 0
        var readGate: CompletableDeferred<Unit>? = null
        var readFailure: Exception? = null

        override suspend fun read(): CachedFirstPage<PlazaPage>? {
            reads++
            readFailure?.let { throw it }
            readGate?.await()
            return snapshot
        }

        override suspend fun write(response: PlazaPage, nextCursor: String?) {
            writes++
            snapshot = CachedFirstPage(response, nextCursor, System.currentTimeMillis())
        }
    }

    @Test
    fun `safety change during disk read discards old posts`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val cached = post(6099)
        val cache = FakeStore(PlazaPage(listOf(cached), null)).apply { readGate = gate }
        val source = source(FakeApi(), cache = { _, _ -> cache })
        val pending = async { source.loadFromCache() }
        runCurrent()
        PlazaRepository.safetyRevision.value += 1
        gate.complete(Unit)
        assertNull(pending.await())
        assertNull(PlazaRepository.cachedEntry(cached.id, 42))
    }

    @Test
    fun `late blocked image response cannot erase a post restored by unblocking`() = runTest(dispatcher) {
        val old = post(6201).copy(images = listOf(PlazaImage("image", 10, 10, "image/png", "https://example.invalid/img", 0)))
        val gate = CompletableDeferred<PlazaPost>()
        val api = FakeApi().apply { postGate = gate }
        val c = controller(api)
        PlazaRepository.cache(old, 42)
        c.ensureFreshImages(old); runCurrent()
        PlazaRepository.changed()
        val fresh = old.copy(text = "restored after unblock", images = emptyList())
        PlazaRepository.cache(fresh, 42)
        gate.completeExceptionally(retrofit2.HttpException(retrofit2.Response.error<Any>(404, okhttp3.ResponseBody.create(null, ""))))
        runCurrent()
        assertSame(fresh, PlazaRepository.cachedEntry(old.id, 42)?.post)
    }

    private inner class FakeApi : PlazaApi {
        override suspend fun report(id: Long, body: ceui.pixiv.plaza.PlazaReportRequest): ceui.pixiv.plaza.PlazaReportReceipt = error("unused")
        override suspend fun blocks(): ceui.pixiv.plaza.PlazaBlocks = error("unused")
        override suspend fun block(uid: Long): ceui.pixiv.plaza.DeletePost = error("unused")
        override suspend fun unblock(uid: Long): ceui.pixiv.plaza.DeletePost = error("unused")

        val pages = ArrayDeque<CompletableDeferred<PlazaPage>>()
        val authors = mutableListOf<Long?>()
        var postGate: CompletableDeferred<PlazaPost>? = null
        var likeFails = false
        var likeGate: CompletableDeferred<PlazaPost>? = null
        var feedCalls = 0
        var likeCalls = 0

        override suspend fun feed(before: Long?, limit: Int, author: Long?, replyTo: Long?): PlazaPage {
            feedCalls++
            authors += author
            return pages.removeFirst().await()
        }

        override suspend fun post(id: Long): PlazaPost = postGate?.await() ?: this@PlazaFeedSourceTest.post(id)

        override suspend fun create(request: CreatePost): PlazaPost = this@PlazaFeedSourceTest.post(10)

        override suspend fun like(id: Long): PlazaPost {
            likeCalls++
            if (likeFails) throw IOException("offline")
            return likeGate?.await() ?: this@PlazaFeedSourceTest.post(id, true)
        }

        override suspend fun unlike(id: Long) = this@PlazaFeedSourceTest.post(id, false)

        override suspend fun react(id: Long, emoji: String) = this@PlazaFeedSourceTest.post(id)

        override suspend fun unreact(id: Long, emoji: String) = this@PlazaFeedSourceTest.post(id)

        override suspend fun reactSticker(id: Long, stickerId: Long) = this@PlazaFeedSourceTest.post(id)

        override suspend fun unreactSticker(id: Long, stickerId: Long) = this@PlazaFeedSourceTest.post(id)

        override suspend fun delete(id: Long) = DeletePost(true)
    }
}
