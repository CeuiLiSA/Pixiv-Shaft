package ceui.pixiv.plaza.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import ceui.pixiv.feeds.cache.CachedFirstPage
import ceui.pixiv.feeds.cache.FeedFirstPageStore
import ceui.pixiv.cache.ObjectPool
import ceui.pixiv.plaza.*
import ceui.pixiv.shaftapi.MediaObject
import ceui.pixiv.shaftapi.MediaUploadResume
import java.io.IOException
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlazaStateTest {
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = ViewModelStore()
    private val cacheWrites = CoroutineScope(SupervisorJob() + dispatcher)
    private var modelKey = 0

    private fun timeline(
        api: FakeApi,
        cache: (Boolean, Long) -> FeedFirstPageStore<PlazaPage>? = { _, _ -> null },
        currentUid: () -> Long = { 42L },
    ) =
        PlazaTimelineViewModel(SavedStateHandle(), api, currentUid, cache, cacheWrites).also {
            viewModels.put("timeline-${modelKey++}", it)
        }

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
        PlazaPost(
            id,
            42,
            "Alice",
            "hello",
            1,
            null,
            null,
            null,
            if (liked) 1 else 0,
            0,
            liked,
            emptyList(),
        )

    @Test
    fun `back interception follows draft contents including unsendable drafts`() {
        val vm = PlazaComposeViewModel(SavedStateHandle(), FakeApi(), { 42L }, { "Alice" })
        assertFalse(vm.shouldInterceptBack())
        vm.title = "title"
        assertTrue(vm.shouldInterceptBack())
        vm.title = ""
        vm.text = "x".repeat(2001)
        assertFalse(vm.canSend())
        assertTrue(vm.shouldInterceptBack())
        vm.text = " \n "
        assertFalse(vm.shouldInterceptBack())
        val uri = Uri.parse("content://draft/image")
        vm.attach(listOf(uri))
        assertTrue(vm.shouldInterceptBack())
        vm.remove(uri.toString())
        assertFalse(vm.shouldInterceptBack())
        vm.reference(123L, "illust")
        assertTrue(vm.shouldInterceptBack())
        vm.reference(null, null)
        assertFalse(vm.shouldInterceptBack())

        val restored = PlazaComposeViewModel(
            SavedStateHandle(mapOf("title" to "restored draft")), FakeApi(), { 42L }, { "Alice" },
        )
        assertTrue(restored.shouldInterceptBack())
    }

    @Test
    fun `composer limits images restores draft and validates all object kinds`() =
        runTest(dispatcher) {
            val saved = SavedStateHandle()
            val api = FakeApi()
            val vm =
                PlazaComposeViewModel(saved, api, { 42 }, { "Alice" }) { _, _, _, _, _ ->
                    error("no upload")
                }
            vm.attach((1..10).map { Uri.parse("content://images/$it") })
            assertEquals(9, vm.state.value.images.size)
            assertNotNull(vm.state.value.error)
            vm.text = "😀".repeat(2000)
            assertTrue(vm.canSend())
            vm.text += "😀"
            assertFalse(vm.canSend())
            vm.text = "draft"
            for (kind in listOf("illust", "manga", "novel", "user")) {
                vm.reference(123, kind)
                assertEquals(kind, vm.state.value.objectType)
            }
            val restored =
                PlazaComposeViewModel(saved, api, { 42 }, { "Alice" }) { _, _, _, _, _ ->
                    error("no upload")
                }
            assertEquals("draft", restored.text)
            assertEquals(9, restored.state.value.images.size)
            assertEquals("user", restored.state.value.objectType)
        }

    @Test
    fun `retry reuses uploaded images and same request id after a lost create response`() =
        runTest(dispatcher) {
            val saved = SavedStateHandle()
            val api = FakeApi()
            var uploads = 0
            val vm =
                PlazaComposeViewModel(saved, api, { 42 }, { "Alice" }) { _, _, _, _, onProgress ->
                    uploads++
                    onProgress(100)
                    MediaObject(
                        "media-$uploads",
                        "key",
                        "image/jpeg",
                        3,
                        10,
                        20,
                        "1",
                        "https://media.pixshaft.com/a",
                        999,
                    )
                }
            vm.attach(listOf(Uri.parse("content://images/1"), Uri.parse("content://images/2")))
            api.createFails = true
            vm.send(RuntimeEnvironment.getApplication().contentResolver)
            runCurrent()
            assertEquals(2, uploads)
            assertNotNull(vm.state.value.error)
            api.createFails = false
            vm.send(RuntimeEnvironment.getApplication().contentResolver)
            runCurrent()
            assertEquals(2, uploads)
            assertEquals(api.creates[0].requestId, api.creates[1].requestId)
            assertEquals(listOf("media-1", "media-2"), api.creates[1].mediaIds)
            assertNotNull(vm.state.value.sentId)
        }

    @Test
    fun `a failed upload keeps its authorisation for the next attempt and drops it on success`() =
        runTest(dispatcher) {
            val saved = SavedStateHandle()
            val api = FakeApi()
            val seen = mutableListOf<MediaUploadResume?>()
            var fails = true
            val uploader:
                suspend (
                    android.content.ContentResolver, Uri, MediaUploadResume?,
                    suspend (MediaUploadResume) -> Unit, (Int) -> Unit,
                ) -> MediaObject =
                { _, _, resume, onResume, _ ->
                    seen += resume
                    if (resume == null)
                        onResume(
                            MediaUploadResume(
                                "media-1", "key", "https://media.pixshaft.com/put",
                                mapOf("content-type" to "image/jpeg"), 999, "image/jpeg", 3,
                            )
                        )
                    if (fails) throw IOException("timeout")
                    MediaObject("media-1", "key", "image/jpeg", 3, 10, 20, "1", "https://media.pixshaft.com/a", 999)
                }
            val resolver = RuntimeEnvironment.getApplication().contentResolver
            val vm = PlazaComposeViewModel(saved, api, { 42 }, { "Alice" }, uploader)
            vm.attach(listOf(Uri.parse("content://images/1")))
            vm.send(resolver)
            runCurrent()
            assertNotNull(vm.state.value.error)
            assertNull(vm.state.value.images.single().mediaId)
            assertNotNull(saved.get<String>("resume:content://images/1"))

            // Process recreation restores the same authorisation.
            fails = false
            val restored = PlazaComposeViewModel(saved, api, { 42 }, { "Alice" }, uploader)
            restored.send(resolver)
            runCurrent()
            assertEquals(listOf(null, "media-1"), seen.map { it?.mediaId })
            assertEquals("media-1", saved.get<String>("media:content://images/1"))
            assertNull(saved.get<String>("resume:content://images/1"))
            assertEquals(listOf("media-1"), api.creates.single().mediaIds)
        }

    @Test
    fun `expired media signatures refresh one post in place and never loop`() =
        runTest(dispatcher) {
            val api = FakeApi()
            val stale = post(3).copy(images = listOf(PlazaImage("m-1", 10, 10, "image/jpeg", "https://media/old", 1)))
            api.pages.add(CompletableDeferred(PlazaPage(listOf(stale), null)))
            val vm = timeline(api)
            vm.enter()
            runCurrent()
            assertEquals("https://media/old", vm.state.value.items.single().images.single().url)
            val fresh = stale.copy(images = listOf(stale.images.single().copy(url = "https://media/new", expiresAt = Long.MAX_VALUE)))
            api.postGate = CompletableDeferred(fresh)
            vm.ensureFreshImages(stale)
            vm.ensureFreshImages(stale)
            runCurrent()
            assertEquals(1, api.postCalls)
            assertEquals("https://media/new", vm.state.value.items.single().images.single().url)
            vm.ensureFreshImages(vm.state.value.items.single())
            runCurrent()
            assertEquals(1, api.postCalls)
        }

    @Test
    fun `inline reply retains failed draft and can send again after consuming success`() =
        runTest(dispatcher) {
            val api = FakeApi()
            val saved = SavedStateHandle()
            val vm = PlazaComposeViewModel(saved, api, { 42 }, { "Alice" }) { _, _, _, _, _ ->
                error("no upload")
            }
            val resolver = RuntimeEnvironment.getApplication().contentResolver
            vm.replyTo = 7
            vm.text = "reply"
            api.createFails = true
            vm.send(resolver)
            vm.send(resolver)
            runCurrent()
            assertEquals(1, api.creates.size)
            assertNull(vm.consumeSentReply())
            assertEquals("reply", vm.text)
            assertEquals(7L, api.creates.single().replyTo)

            api.createFails = false
            vm.send(resolver)
            runCurrent()
            assertEquals(api.creates[0].requestId, api.creates[1].requestId)
            assertEquals(10L, vm.consumeSentReply())
            assertNull(vm.consumeSentReply())
            assertEquals("", vm.text)
            assertFalse(vm.canSend())
            vm.text = "reply"
            vm.send(resolver)
            runCurrent()
            assertNotEquals(api.creates[1].requestId, api.creates[2].requestId)
        }

    @Test
    fun `changing reply target after a failure uses a new request id`() = runTest(dispatcher) {
        val api = FakeApi()
        val vm = PlazaComposeViewModel(SavedStateHandle(), api, { 42 }, { "Alice" }) { _, _, _, _, _ ->
            error("no upload")
        }
        vm.replyTo = 7
        vm.text = "reply"
        api.createFails = true
        vm.send(RuntimeEnvironment.getApplication().contentResolver)
        runCurrent()
        vm.replyTo = 8
        api.createFails = false
        vm.send(RuntimeEnvironment.getApplication().contentResolver)
        runCurrent()
        assertEquals(8L, api.creates.last().replyTo)
        assertNotEquals(api.creates[0].requestId, api.creates[1].requestId)
    }

    @Test
    fun `account changes prevent publishing a previous accounts draft`() =
        runTest(dispatcher) {
            var uid = 42L
            val api = FakeApi()
            val vm =
                PlazaComposeViewModel(SavedStateHandle(), api, { uid }, { "Alice" }) { _, _, _, _, _ ->
                    error("no upload")
                }
            vm.text = "draft"
            uid = 99
            vm.send(RuntimeEnvironment.getApplication().contentResolver)
            runCurrent()
            assertTrue(api.creates.isEmpty())
            assertNotNull(vm.state.value.error)
            assertEquals("draft", vm.text)
        }

    @Test
    fun `refresh cancels stale pagination and failed likes leave counts unchanged`() =
        runTest(dispatcher) {
            val api = FakeApi()
            api.pages.add(CompletableDeferred(PlazaPage(listOf(post(3)), 3)))
            val stale = CompletableDeferred<PlazaPage>()
            api.pages.add(stale)
            api.pages.add(CompletableDeferred(PlazaPage(listOf(post(4)), null)))
            val vm = timeline(api)
            vm.enter()
            runCurrent()
            vm.more()
            runCurrent()
            vm.refresh()
            runCurrent()
            stale.complete(PlazaPage(listOf(post(2)), null))
            runCurrent()
            assertEquals(listOf(4L), vm.state.value.items.map { it.id })
            api.likeFails = true
            vm.like(post(4))
            runCurrent()
            assertEquals(0, vm.state.value.items.single().likeCount)
            assertTrue(vm.state.value.busyIds.isEmpty())
            assertNotNull(vm.state.value.error)
        }

    @Test
    fun `refresh during mutation waits and duplicate taps make one request`() =
        runTest(dispatcher) {
            val api = FakeApi()
            api.pages.add(CompletableDeferred(PlazaPage(listOf(post(4)), null)))
            api.pages.add(CompletableDeferred(PlazaPage(listOf(post(4, true)), null)))
            api.likeGate = CompletableDeferred()
            val vm = timeline(api)
            vm.enter()
            runCurrent()
            vm.like(post(4))
            vm.like(post(4))
            runCurrent()
            vm.refresh()
            runCurrent()
            assertEquals(1, api.feedCalls)
            api.likeGate!!.complete(post(4, true))
            runCurrent()
            assertEquals(1, api.likeCalls)
            assertEquals(2, api.feedCalls)
            assertTrue(vm.state.value.items.single().liked)
        }

    @Test
    fun `list warms ObjectPool and detail displays it before comments with no post request`() = runTest(dispatcher) {
        val api = FakeApi()
        val cached = post(1001).copy(title = "Cached title")
        api.pages.add(CompletableDeferred(PlazaPage(listOf(cached), null)))
        val list = timeline(api)
        list.enter()
        runCurrent()
        assertSame(cached, PlazaRepository.cachedEntry(cached.id, 42)?.post)
        val cachedAt = PlazaRepository.cachedEntry(cached.id, 42)!!.cachedAt
        val comments = CompletableDeferred<PlazaPage>()
        api.pages.add(comments)
        val detail = timeline(api)
        detail.enter(cached.id)
        assertSame(cached, detail.state.value.parent)
        runCurrent()
        assertSame(cached, detail.state.value.parent)
        assertEquals(0, api.postCalls)
        assertTrue(detail.state.value.loading)
        assertEquals(cachedAt, PlazaRepository.cachedEntry(cached.id, 42)!!.cachedAt)
        comments.complete(PlazaPage(emptyList(), null))
        runCurrent()
        assertFalse(detail.state.value.loading)
        api.pages.add(CompletableDeferred(PlazaPage(emptyList(), null)))
        detail.refresh()
        runCurrent()
        assertEquals(1, api.postCalls)
    }

    @Test
    fun `cold detail publishes the post before slow or failed comments`() = runTest(dispatcher) {
        val api = FakeApi()
        val comments = CompletableDeferred<PlazaPage>()
        api.pages.add(comments)
        val detail = timeline(api)
        detail.enter(1002)
        runCurrent()
        assertEquals(1002L, detail.state.value.parent?.id)
        assertSame(detail.state.value.parent, PlazaRepository.cachedEntry(1002, 42)?.post)
        assertTrue(detail.state.value.loading)
        comments.completeExceptionally(IOException("comments offline"))
        runCurrent()
        assertEquals(1002L, detail.state.value.parent?.id)
        assertNotNull(detail.state.value.error)
    }

    @Test
    fun `pooled likes and deletion update the list and detail together`() = runTest(dispatcher) {
        val api = FakeApi()
        val cached = post(1003)
        api.pages.add(CompletableDeferred(PlazaPage(listOf(cached), null)))
        val list = timeline(api)
        list.enter()
        runCurrent()
        api.pages.add(CompletableDeferred(PlazaPage(emptyList(), null)))
        val detail = timeline(api)
        detail.enter(cached.id)
        runCurrent()
        detail.like(cached)
        runCurrent()
        assertTrue(list.state.value.items.single().liked)
        assertSame(list.state.value.items.single(), detail.state.value.parent)
        detail.delete(detail.state.value.parent!!)
        runCurrent()
        assertTrue(list.state.value.items.isEmpty())
        assertNull(detail.state.value.parent)
        assertNull(PlazaRepository.cachedEntry(cached.id, 42)?.post)
        viewModels.clear()
        assertFalse(ObjectPool.get<PlazaPostCacheEntry>(cached.id).hasObservers())
    }

    @Test
    fun `other accounts cache and late responses cannot cross account boundaries`() = runTest(dispatcher) {
        val cached = post(1004).copy(liked = true)
        PlazaRepository.cache(cached, 42)
        val api = FakeApi().apply { postGate = CompletableDeferred() }
        var uid = 99L
        val detail = timeline(api) { uid }
        detail.enter(cached.id)
        assertNull(detail.state.value.parent)
        runCurrent()
        uid = 100L
        api.postGate!!.complete(post(cached.id))
        runCurrent()
        assertNull(detail.state.value.parent)
        assertNull(PlazaRepository.cachedEntry(cached.id, 99))
        assertSame(cached, PlazaRepository.cachedEntry(cached.id, 42)?.post)
    }

    @Test
    fun `expired media stays visible while refreshed post replaces signed urls`() = runTest(dispatcher) {
        val cached = post(1005).copy(images = listOf(
            PlazaImage("image", 10, 20, "image/png", "https://example.invalid/expired", 1),
        ))
        PlazaRepository.cache(cached, 42)
        val api = FakeApi().apply { postGate = CompletableDeferred() }
        api.pages.add(CompletableDeferred(PlazaPage(emptyList(), null)))
        val detail = timeline(api)
        detail.enter(cached.id)
        assertSame(cached, detail.state.value.parent)
        runCurrent()
        assertEquals(1, api.postCalls)
        val updated = cached.copy(images = listOf(cached.images.single().copy(
            url = "https://example.invalid/fresh", expiresAt = Long.MAX_VALUE,
        )))
        api.postGate!!.complete(updated)
        runCurrent()
        assertSame(updated, detail.state.value.parent)
        assertTrue(PlazaRepository.hasFreshPost(cached.id, 42))
    }

    @Test
    fun `stale refresh cannot overwrite a reaction changed from another screen`() = runTest(dispatcher) {
        val cached = post(1006)
        val listApi = FakeApi()
        listApi.pages.add(CompletableDeferred(PlazaPage(listOf(cached), null)))
        val list = timeline(listApi)
        list.enter()
        runCurrent()
        val detailApi = FakeApi()
        detailApi.pages.add(CompletableDeferred(PlazaPage(emptyList(), null)))
        val detail = timeline(detailApi)
        detail.enter(cached.id)
        runCurrent()
        val stale = CompletableDeferred<PlazaPage>()
        listApi.pages.add(stale)
        list.refresh()
        runCurrent()
        detail.like(cached)
        runCurrent()
        assertTrue(list.state.value.items.single().liked)
        listApi.pages.add(CompletableDeferred(PlazaPage(listOf(post(cached.id, true)), null)))
        stale.complete(PlazaPage(listOf(cached), null))
        runCurrent()
        assertTrue(list.state.value.items.single().liked)
        assertTrue(detail.state.value.parent!!.liked)
        assertEquals(3, listApi.feedCalls)
    }

    @Test
    fun `complete post updates can clear reactions and cached deleted posts stay removed`() = runTest(dispatcher) {
        val cached = post(1007).copy(reactions = listOf(PlazaReaction("smile", 1, true)))
        PlazaRepository.cache(cached, 42)
        val api = FakeApi()
        api.pages.add(CompletableDeferred(PlazaPage(emptyList(), null)))
        val detail = timeline(api)
        detail.enter(cached.id)
        runCurrent()
        PlazaRepository.cache(cached.copy(reactions = emptyList()), 42)
        assertTrue(detail.state.value.parent!!.reactions.isEmpty())
        api.postFailure = retrofit2.HttpException(retrofit2.Response.error<PlazaPost>(
            404, "not found".toResponseBody(),
        ))
        detail.refresh()
        runCurrent()
        assertNull(detail.state.value.parent)
        assertNull(PlazaRepository.cachedEntry(cached.id, 42)?.post)
    }

    @Test
    fun `disk first page is visible before network and survives offline refresh`() = runTest(dispatcher) {
        val cached = post(2001)
        val store = FakeStore(PlazaPage(listOf(cached), 2001))
        val network = CompletableDeferred<PlazaPage>()
        val api = FakeApi().apply { pages.add(network) }
        val vm = timeline(api, cache = { _, _ -> store })
        vm.enter()
        runCurrent()
        assertSame(cached, vm.state.value.items.single())
        assertTrue(vm.state.value.loading)
        assertSame(cached, PlazaRepository.cachedEntry(cached.id, 42)?.post)
        assertFalse(PlazaRepository.hasFreshPost(cached.id, 42))
        vm.more()
        assertEquals(1, api.feedCalls)
        network.completeExceptionally(IOException("offline"))
        runCurrent()
        assertSame(cached, vm.state.value.items.single())
        assertFalse(vm.state.value.loading)
        assertNotNull(vm.state.value.error)
        assertEquals(0, store.writes)

        // Clicking a disk-restored post still shows it immediately, then validates old data.
        val detailApi = FakeApi().apply { postGate = CompletableDeferred() }
        val detail = timeline(detailApi)
        detail.enter(cached.id)
        assertSame(cached, detail.state.value.parent)
        runCurrent()
        assertEquals(1, detailApi.postCalls)
    }

    @Test
    fun `disk restoration preserves newer pooled reactions and deletion tombstones`() = runTest(dispatcher) {
        val stale = post(2002)
        val deleted = post(2003)
        val updated = stale.copy(liked = true, likeCount = 1)
        PlazaRepository.cache(updated, 42)
        PlazaRepository.invalidate(deleted.id, 42)
        val store = FakeStore(PlazaPage(listOf(stale, deleted), null))
        val api = FakeApi().apply { pages.add(CompletableDeferred()) }
        val vm = timeline(api, cache = { _, _ -> store })
        vm.enter()
        runCurrent()
        assertSame(updated, vm.state.value.items.single())
        assertSame(updated, PlazaRepository.cachedEntry(stale.id, 42)?.post)
        assertNull(PlazaRepository.cachedEntry(deleted.id, 42)?.post)
    }

    @Test
    fun `only network first pages replace disk snapshot and refresh never rolls back to disk`() = runTest(dispatcher) {
        val store = FakeStore(PlazaPage(listOf(post(2004)), 2004))
        val first = PlazaPage(listOf(post(2005)), 2005)
        val api = FakeApi().apply { pages.add(CompletableDeferred(first)) }
        val vm = timeline(api, cache = { _, _ -> store })
        vm.enter()
        runCurrent()
        assertEquals(first, store.snapshot!!.payload)
        assertEquals("2005", store.snapshot!!.nextCursor)
        api.pages.add(CompletableDeferred(PlazaPage(listOf(post(2006)), null)))
        vm.more()
        runCurrent()
        assertEquals(listOf(2005L, 2006L), vm.state.value.items.map { it.id })
        assertEquals(1, store.writes)
        assertEquals(first, store.snapshot!!.payload)
        api.pages.add(CompletableDeferred())
        vm.refresh()
        runCurrent()
        assertEquals(listOf(2005L, 2006L), vm.state.value.items.map { it.id })
        assertEquals(1, store.reads)
    }

    @Test
    fun `all and mine snapshots switch with account and late cache read cannot leak`() = runTest(dispatcher) {
        var uid = 42L
        val all = FakeStore(PlazaPage(listOf(post(2007)), null))
        val mine = FakeStore(PlazaPage(listOf(post(2008)), null))
        val other = FakeStore(PlazaPage(listOf(post(2009)), null))
        val keys = mutableListOf<Pair<Boolean, Long>>()
        val api = FakeApi().apply { repeat(3) { pages.add(CompletableDeferred()) } }
        val vm = timeline(api, cache = { own, account ->
            keys += own to account
            when { account == 99L -> other; own -> mine; else -> all }
        }, currentUid = { uid })
        vm.enter()
        runCurrent()
        assertEquals(2007L, vm.state.value.items.single().id)
        vm.selectMine(true)
        runCurrent()
        assertEquals(2008L, vm.state.value.items.single().id)
        uid = 99
        vm.enter()
        assertTrue(vm.state.value.items.isEmpty())
        runCurrent()
        assertEquals(2009L, vm.state.value.items.single().id)
        assertEquals(listOf(false to 42L, true to 42L, true to 99L), keys)

        val delayed = FakeStore(PlazaPage(listOf(post(2010)), null)).apply {
            readGate = CompletableDeferred()
        }
        val second = timeline(FakeApi(), cache = { _, _ -> delayed }, currentUid = { uid })
        second.enter()
        runCurrent()
        uid = 100
        delayed.readGate!!.complete(Unit)
        runCurrent()
        assertTrue(second.state.value.items.isEmpty())
        assertNull(PlazaRepository.cachedEntry(2010, 99))
    }

    @Test
    fun `unreadable and structurally broken snapshots fall back to network`() = runTest(dispatcher) {
        for (brokenShape in listOf(false, true)) {
            val page = if (brokenShape) com.google.gson.Gson().fromJson(
                "{}", PlazaPage::class.java,
            ) else PlazaPage(emptyList(), null)
            val store = FakeStore(page).apply {
                if (!brokenShape) readFailure = IOException("disk unavailable")
            }
            val fresh = post(if (brokenShape) 2011 else 2012)
            val api = FakeApi().apply { pages.add(CompletableDeferred(PlazaPage(listOf(fresh), null))) }
            val vm = timeline(api, cache = { _, _ -> store })
            vm.enter()
            runCurrent()
            assertSame(fresh, vm.state.value.items.single())
            assertFalse(vm.state.value.loading)
            assertFalse(vm.state.value.restoringCache)
            assertNull(vm.state.value.error)
            assertEquals(1, store.writes)
        }
    }

    @Test
    fun `snapshot writes survive page closure and older write cannot replace newer first page`() = runTest(dispatcher) {
        val store = FakeStore(PlazaPage(emptyList(), null)).apply { writeGate = CompletableDeferred() }
        val first = PlazaPage(listOf(post(2013)), null)
        val second = PlazaPage(listOf(post(2014)), null)
        val api = FakeApi().apply { pages.add(CompletableDeferred(first)) }
        val vm = timeline(api, cache = { _, _ -> store })
        vm.enter()
        runCurrent()
        assertFalse(vm.state.value.loading) // Disk IO never delays content.
        assertSame(first.items.single(), vm.state.value.items.single())
        api.pages.add(CompletableDeferred(second))
        vm.refresh()
        runCurrent()
        assertSame(second.items.single(), vm.state.value.items.single())
        viewModels.clear()
        store.writeGate!!.complete(Unit)
        runCurrent()
        assertEquals(2, store.writes)
        assertEquals(second, store.snapshot!!.payload)
    }

    @Test
    fun `cross screen mutations cannot overwrite each others full post responses`() = runTest(dispatcher) {
        val original = post(2015)
        val api = FakeApi().apply {
            pages.add(CompletableDeferred(PlazaPage(listOf(original), null)))
            pages.add(CompletableDeferred(PlazaPage(emptyList(), null)))
            likeGate = CompletableDeferred()
            reactResult = original.copy(liked = true, likeCount = 1,
                reactions = listOf(PlazaReaction("😂", 1, true)))
        }
        val list = timeline(api)
        val detail = timeline(api)
        list.enter()
        runCurrent()
        detail.enter(original.id)
        runCurrent()
        list.like(original)
        runCurrent()
        detail.react(original, "😂")
        runCurrent()
        assertEquals(0, api.reactCalls)
        api.likeGate!!.complete(original.copy(liked = true, likeCount = 1))
        runCurrent()
        assertEquals(1, api.reactCalls)
        assertTrue(list.state.value.items.single().liked)
        assertEquals(1, list.state.value.items.single().reactions.size)
        assertSame(list.state.value.items.single(), detail.state.value.parent)
    }

    @Test
    fun `cached post accepts interactions while comments load and resumes comments after success or failure`() = runTest(dispatcher) {
        for (fails in listOf(false, true)) {
            val cached = post(if (fails) 3001 else 3002)
            PlazaRepository.cache(cached, 42)
            val comments = CompletableDeferred<PlazaPage>()
            val api = FakeApi().apply {
                likeFails = fails
                pages.add(comments)
                pages.add(CompletableDeferred(PlazaPage(emptyList(), null)))
            }
            val detail = timeline(api)
            detail.enter(cached.id)
            runCurrent()
            assertTrue(detail.state.value.loading)
            detail.like(cached)
            runCurrent()
            assertEquals("Visible cached posts must accept taps during comment loading", 1, api.likeCalls)
            assertEquals(!fails, detail.state.value.parent!!.liked)
            comments.complete(PlazaPage(emptyList(), null))
            runCurrent()
            assertEquals(if (fails) 1 else 2, api.feedCalls)
            assertEquals(0, api.postCalls)
            assertFalse(detail.state.value.loading)
            assertEquals(fails, detail.state.value.error != null)
            assertEquals(!fails, detail.state.value.parent!!.liked)
            assertTrue(detail.state.value.busyIds.isEmpty())
        }
    }

    @Test
    fun `not found invalidation prevents an older list response from resurrecting the post`() = runTest(dispatcher) {
        val cached = post(3003)
        PlazaRepository.cache(cached, 42)
        val stale = CompletableDeferred<PlazaPage>()
        val listApi = FakeApi().apply {
            pages.add(stale)
            pages.add(CompletableDeferred(PlazaPage(emptyList(), null)))
        }
        val list = timeline(listApi)
        list.enter()
        runCurrent()
        val detailApi = FakeApi().apply {
            postFailure = retrofit2.HttpException(retrofit2.Response.error<PlazaPost>(
                404, "not found".toResponseBody(),
            ))
        }
        val detail = timeline(detailApi)
        detail.enter(cached.id)
        detail.refresh()
        runCurrent()
        assertNull(detail.state.value.parent)
        stale.complete(PlazaPage(listOf(cached), null))
        runCurrent()
        assertNull(PlazaRepository.cachedEntry(cached.id, 42)?.post)
        assertNull(detail.state.value.parent)
        assertTrue(list.state.value.items.isEmpty())
        assertEquals(2, listApi.feedCalls)
    }

    @Test
    fun `deleting a cached parent cancels pending comments without a redundant not found request`() = runTest(dispatcher) {
        val cached = post(3004)
        PlazaRepository.cache(cached, 42)
        val comments = CompletableDeferred<PlazaPage>()
        val api = FakeApi().apply { pages.add(comments) }
        val detail = timeline(api)
        detail.enter(cached.id)
        runCurrent()
        detail.delete(cached)
        runCurrent()
        assertNull(detail.state.value.parent)
        assertFalse(detail.state.value.loading)
        comments.complete(PlazaPage(listOf(post(3005)), null))
        runCurrent()
        assertTrue(detail.state.value.items.isEmpty())
        assertNull(detail.state.value.error)
        assertEquals(0, api.postCalls)
        assertEquals(1, api.feedCalls)
    }

    @Test
    fun `malformed nested snapshot fields never enter visible state or ObjectPool`() = runTest(dispatcher) {
        val gson = com.google.gson.Gson()
        val paths = listOf("displayName", "text", "title", "images", "reactions", "commentsPreview",
            "images.0", "images.0.url", "reactions.0.emoji", "commentsPreview.0.text")
        for ((index, path) in paths.withIndex()) {
            val valid = post(4000L + index * 2)
            val broken = post(valid.id + 1).copy(
                images = listOf(PlazaImage("image", 10, 10, "image/png", "https://example.invalid/a", Long.MAX_VALUE)),
                reactions = listOf(PlazaReaction("smile", 1, false)),
                commentsPreview = listOf(PlazaCommentPreview(1, 42, "Alice", "reply")),
            )
            val json = gson.toJsonTree(broken).asJsonObject
            when (path) {
                "images.0" -> json.getAsJsonArray("images").set(0, com.google.gson.JsonNull.INSTANCE)
                "images.0.url" -> json.getAsJsonArray("images")[0].asJsonObject.remove("url")
                "reactions.0.emoji" -> json.getAsJsonArray("reactions")[0].asJsonObject.remove("emoji")
                "commentsPreview.0.text" -> json.getAsJsonArray("commentsPreview")[0].asJsonObject.remove("text")
                else -> json.remove(path)
            }
            val page = PlazaPage(listOf(valid, gson.fromJson(json, PlazaPost::class.java)), null)
            val network = CompletableDeferred<PlazaPage>()
            val api = FakeApi().apply { pages.add(network) }
            val vm = timeline(api, cache = { _, _ -> FakeStore(page) })
            vm.enter()
            runCurrent()
            assertTrue("Corrupt $path must not reach the adapter", vm.state.value.items.isEmpty())
            assertNull("Validate the whole snapshot before seeding any entry", PlazaRepository.cachedEntry(valid.id, 42))
            assertNull(PlazaRepository.cachedEntry(broken.id, 42))
            assertEquals(1, api.feedCalls)
            network.complete(PlazaPage(listOf(valid), null))
            runCurrent()
            assertEquals(listOf(valid), vm.state.value.items)
            assertNull(vm.state.value.error)
        }
    }

    private class FakeStore(page: PlazaPage) : FeedFirstPageStore<PlazaPage> {
        var snapshot: CachedFirstPage<PlazaPage>? = CachedFirstPage(page, page.nextBefore?.toString(), 100)
        var reads = 0
        var writes = 0
        var readGate: CompletableDeferred<Unit>? = null
        var writeGate: CompletableDeferred<Unit>? = null
        var readFailure: Exception? = null
        override suspend fun read(): CachedFirstPage<PlazaPage>? {
            reads++
            readFailure?.let { throw it }
            readGate?.await()
            return snapshot
        }
        override suspend fun write(response: PlazaPage, nextCursor: String?) {
            writeGate?.await()
            writes++
            snapshot = CachedFirstPage(response, nextCursor, System.currentTimeMillis())
        }
    }

    private inner class FakeApi : PlazaApi {
        val pages = ArrayDeque<CompletableDeferred<PlazaPage>>()
        val creates = mutableListOf<CreatePost>()
        var createFails = false
        var likeFails = false
        var likeGate: CompletableDeferred<PlazaPost>? = null
        var feedCalls = 0
        var likeCalls = 0
        var postCalls = 0
        var reactCalls = 0
        var reactResult: PlazaPost? = null
        var postGate: CompletableDeferred<PlazaPost>? = null
        var postFailure: Exception? = null

        override suspend fun feed(
            before: Long?,
            limit: Int,
            author: Long?,
            replyTo: Long?,
        ): PlazaPage {
            feedCalls++
            return pages.removeFirst().await()
        }

        override suspend fun post(id: Long): PlazaPost {
            postCalls++
            postFailure?.let { throw it }
            return postGate?.await() ?: this@PlazaStateTest.post(id)
        }

        override suspend fun create(request: CreatePost): PlazaPost {
            creates += request
            if (createFails) throw IOException("lost")
            return this@PlazaStateTest.post(10)
        }

        override suspend fun like(id: Long): PlazaPost {
            likeCalls++
            if (likeFails) throw IOException("offline")
            return likeGate?.await() ?: this@PlazaStateTest.post(id, true)
        }

        override suspend fun unlike(id: Long) = this@PlazaStateTest.post(id, false)

        override suspend fun react(id: Long, emoji: String): PlazaPost {
            reactCalls++
            return reactResult ?: this@PlazaStateTest.post(id)
        }

        override suspend fun unreact(id: Long, emoji: String) = this@PlazaStateTest.post(id)
        override suspend fun reactSticker(id: Long, stickerId: Long) = this@PlazaStateTest.post(id)
        override suspend fun unreactSticker(id: Long, stickerId: Long) = this@PlazaStateTest.post(id)

        override suspend fun delete(id: Long) = DeletePost(true)
    }
}
