package ceui.pixiv.plaza.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import ceui.lisa.network.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class PlazaStateTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val repo = FakeRepository()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() {
        store.clear()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private inline fun <reified T : ViewModel> keep(vm: T): T {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V = vm as V
        }
        return ViewModelProvider(store, factory)[T::class.java]
    }

    private fun feed() = keep(PlazaViewModel(repo) { 7L })
    private fun detail() = keep(PlazaPostDetailViewModel(10L, repo) { 7L })
    private fun runPending() = dispatcher.scheduler.runCurrent()
    private fun enqueueFeed() = CompletableDeferred<PlazaResult<PlazaFeedResponse>>().also { repo.feeds.add(it) }

    @Test fun `restored feed loads once and returning view keeps loaded data`() {
        val response = enqueueFeed()
        val vm = feed()
        vm.ensureLoaded(context)
        vm.ensureLoaded(context)
        runPending()
        assertEquals(1, repo.feedCalls.size)
        response.complete(PlazaResult.Ok(PlazaFeedResponse(listOf(post(10)), null)))
        runPending()
        vm.ensureLoaded(context)
        runPending()
        assertEquals(1, repo.feedCalls.size)
        assertFalse(vm.state.value.isInitialLoading)
    }

    @Test fun `refresh cancels old pagination and preserves new cursor`() {
        val first = enqueueFeed()
        val vm = feed()
        vm.load(context)
        first.complete(PlazaResult.Ok(PlazaFeedResponse(listOf(post(10)), 10)))
        runPending()
        val page = enqueueFeed()
        vm.loadMore(context)
        runPending()
        val refresh = enqueueFeed()
        vm.load(context, true)
        runPending()
        refresh.complete(PlazaResult.Ok(PlazaFeedResponse(listOf(post(30)), 30)))
        page.complete(PlazaResult.Ok(PlazaFeedResponse(listOf(post(9)), 9)))
        runPending()
        assertEquals(listOf(30L), vm.state.value.items.map { it.id })
        enqueueFeed()
        vm.loadMore(context)
        runPending()
        assertEquals(listOf(null, 10L, null, 30L), repo.feedCalls)
    }

    @Test fun `stale refresh cannot resurrect deleted posts or erase new posts`() {
        val response = enqueueFeed()
        val vm = feed()
        vm.load(context)
        runPending()
        repo.plazaPostsCreated.tryEmit(post(12))
        repo.plazaPostsDeleted.tryEmit(10)
        runPending()
        response.complete(PlazaResult.Ok(PlazaFeedResponse(listOf(post(10), post(9)), null)))
        runPending()
        assertEquals(listOf(12L, 9L), vm.state.value.items.map { it.id })
    }

    @Test fun `overlapping pages do not duplicate cards`() {
        val first = enqueueFeed()
        val vm = feed()
        vm.load(context)
        first.complete(PlazaResult.Ok(PlazaFeedResponse(listOf(post(10)), 10)))
        runPending()
        val page = enqueueFeed()
        vm.loadMore(context)
        page.complete(PlazaResult.Ok(PlazaFeedResponse(listOf(post(10), post(9)), null)))
        runPending()
        assertEquals(listOf(10L, 9L), vm.state.value.items.map { it.id })
    }

    @Test fun `failed detail is retryable without recreating its activity`() {
        repo.postResponse = PlazaResult.Err(0, "network", null)
        val vm = detail()
        runPending()
        assertEquals("network", vm.state.value.loadError)
        assertNull(vm.state.value.post)
        repo.postResponse = PlazaResult.Ok(post(10))
        vm.retry()
        runPending()
        assertEquals(10L, vm.state.value.post?.id)
        assertNull(vm.state.value.loadError)
    }

    @Test fun `comment load error remains an error and retry restores comments`() {
        repo.commentsResponse = PlazaResult.Err(0, "network", null)
        val vm = detail()
        runPending()
        assertEquals("network", vm.state.value.commentsError)
        repo.commentsResponse = PlazaResult.Ok(PlazaCommentsResponse(10, 1, listOf(comment()), null))
        vm.retryComments()
        runPending()
        assertNull(vm.state.value.commentsError)
        assertEquals(1, vm.state.value.comments.size)
    }

    @Test fun `like failure preserves a simultaneously posted comment count`() {
        val vm = detail()
        runPending()
        vm.toggleLike(context)
        runPending()
        vm.postComment(context, "hello")
        runPending()
        assertEquals(1, vm.state.value.post?.comment_count)
        repo.like.complete(PlazaResult.Err(0, "network", null))
        runPending()
        assertEquals(1, vm.state.value.post?.comment_count)
        assertEquals(0, vm.state.value.post?.like_count)
        assertEquals(false, vm.state.value.post?.liked_by_viewer)
        assertEquals(false, repo.lastBroadcast?.liked_by_viewer)
        assertEquals(1, repo.lastBroadcast?.comment_count)
    }

    @Test fun `duplicate taps issue only one like request`() {
        val vm = detail()
        runPending()
        vm.toggleLike(context)
        vm.toggleLike(context)
        runPending()
        assertEquals(1, repo.likeCalls)
        repo.like.complete(PlazaResult.Ok(PlazaLikeResponse(true, added = true, like_count = 1)))
        runPending()
        assertEquals(true, vm.state.value.post?.liked_by_viewer)
        assertEquals(1, vm.state.value.post?.like_count)
    }

    @Test fun `deleted post is not restored by a late like failure`() {
        val vm = detail()
        runPending()
        vm.toggleLike(context)
        runPending()
        repo.plazaPostsDeleted.tryEmit(10)
        runPending()
        repo.like.complete(PlazaResult.Err(0, "network", null))
        runPending()
        assertTrue(vm.state.value.isGone)
        assertNull(vm.state.value.post)
    }

    private class FakeRepository : PlazaRepository() {
        override val plazaPostsCreated = MutableSharedFlow<PlazaPost>(extraBufferCapacity = 8)
        override val plazaPostsDeleted = MutableSharedFlow<Long>(extraBufferCapacity = 8)
        override val plazaPostsUpdated = MutableSharedFlow<PlazaPost>(extraBufferCapacity = 8)
        val feeds = ArrayDeque<CompletableDeferred<PlazaResult<PlazaFeedResponse>>>()
        val feedCalls = mutableListOf<Long?>()
        var postResponse: PlazaResult<PlazaPost> = PlazaResult.Ok(post(10))
        var commentsResponse: PlazaResult<PlazaCommentsResponse> =
            PlazaResult.Ok(PlazaCommentsResponse(10, 0, emptyList(), null))
        val like = CompletableDeferred<PlazaResult<PlazaLikeResponse>>()
        var likeCalls = 0
        var lastBroadcast: PlazaPost? = null
        override fun cachedPlazaPost(id: Long): PlazaPost? = null
        override fun broadcastPostUpdated(post: PlazaPost) {
            lastBroadcast = post
            plazaPostsUpdated.tryEmit(post)
        }
        override suspend fun listPlazaFeed(limit: Int, before: Long?, viewerUid: Long): PlazaResult<PlazaFeedResponse> {
            feedCalls += before
            return feeds.removeFirst().await()
        }
        override suspend fun getPlazaPost(id: Long, viewerUid: Long) = postResponse
        override suspend fun listPlazaComments(postId: Long, limit: Int, before: Long?) = commentsResponse
        override suspend fun likePlazaPost(uid: Long, postId: Long): PlazaResult<PlazaLikeResponse> {
            likeCalls++
            return like.await()
        }
        override suspend fun createPlazaComment(uid: Long, postId: Long, text: String): PlazaResult<PlazaComment> =
            PlazaResult.Ok(comment())
    }

    companion object {
        private fun post(id: Long) = PlazaPost(id, 7, "author", "body", 1,
            PlazaPostRefs(), liked_by_viewer = false)
        private fun comment() = PlazaComment(1, 10, 7, "author", "hello", 1)
    }
}
