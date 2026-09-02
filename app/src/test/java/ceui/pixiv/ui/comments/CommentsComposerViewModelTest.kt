package ceui.pixiv.ui.comments

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import ceui.pixiv.api.API
import ceui.pixiv.api.model.Comment
import ceui.pixiv.api.model.CommentResponse
import ceui.pixiv.api.model.ObjectType
import ceui.pixiv.api.model.PostCommentResponse
import ceui.loxia.User
import ceui.pixiv.feeds.FeedItem
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommentsComposerViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @Test
    fun `successful send clears only the submitted draft and reply`() = runTest {
        val api = ControllableCommentApi()
        val viewModel = viewModel(api)
        val reply = comment(id = 10L)
        viewModel.updateDraft("submitted")
        viewModel.startReply(reply, parentCommentId = 8L)

        val result = async { viewModel.sendComment() }
        runCurrent()
        api.complete(comment(id = 20L))

        assertEquals(SentComment(8L, comment(id = 20L)), result.await())
        assertEquals("", viewModel.editingComment.value)
        assertNull(viewModel.replyToComment.value)
    }

    @Test
    fun `send completion preserves edits and reply selected while request is in flight`() = runTest {
        val api = ControllableCommentApi()
        val viewModel = viewModel(api)
        viewModel.updateDraft("submitted")
        viewModel.startReply(comment(id = 10L), parentCommentId = 8L)

        val result = async { viewModel.sendComment() }
        runCurrent()
        val newerReply = comment(id = 11L)
        viewModel.updateDraft("new draft")
        viewModel.startReply(newerReply, parentCommentId = 9L)
        api.complete(comment(id = 20L))

        assertEquals(SentComment(8L, comment(id = 20L)), result.await())
        assertEquals("new draft", viewModel.editingComment.value)
        assertEquals(newerReply, viewModel.replyToComment.value)
    }

    @Test
    fun `missing comment response preserves local editing state`() = runTest {
        val api = ControllableCommentApi()
        val viewModel = viewModel(api)
        val reply = comment(id = 10L)
        viewModel.updateDraft("keep me")
        viewModel.startReply(reply, parentCommentId = 8L)

        supervisorScope {
            val result = async { viewModel.sendComment() }
            runCurrent()
            api.complete(comment = null)
            assertTrue(runCatching { result.await() }.exceptionOrNull() is CommentProtocolException)
        }
        assertEquals("keep me", viewModel.editingComment.value)
        assertEquals(reply, viewModel.replyToComment.value)
    }

    @Test
    fun `text and stamp submissions use one single flight`() = runTest {
        val api = ControllableCommentApi()
        val viewModel = viewModel(api)
        viewModel.updateDraft("submitted")

        val textResult = async { viewModel.sendComment() }
        runCurrent()
        assertNull(viewModel.sendStamp(stampId = 1001L))
        assertEquals(1, api.submissionCount)

        api.complete(comment(id = 20L))
        assertEquals(20L, textResult.await()?.comment?.id)
    }

    @Test
    fun `local reply does not mark a collapsed server thread as loaded`() {
        val viewModel = viewModel(ControllableCommentApi())
        val parent = CommentFeedItem(
            comment = comment(id = 10L, hasReplies = true),
            illustArthurId = 1L,
        )
        val items: List<FeedItem> = listOf(parent)

        val updated = viewModel.applySentComment(
            items = items,
            parentCommentId = 10L,
            comment = comment(id = 20L),
            illustArthurId = 1L,
        ).single() as CommentFeedItem

        assertEquals(listOf(20L), updated.childComments.map { it.id })
        assertTrue(updated.comment.has_replies)
        assertTrue(!updated.repliesLoaded)
    }

    @Test
    fun `deleting a local reply preserves collapsed server reply metadata`() {
        val viewModel = viewModel(ControllableCommentApi())
        val parent = CommentFeedItem(
            comment = comment(id = 10L, hasReplies = true),
            illustArthurId = 1L,
            childComments = listOf(comment(id = 20L)),
            repliesLoaded = false,
        )
        val items: List<FeedItem> = listOf(parent)

        val updated = viewModel.applyDeletedComment(
            items = items,
            commentId = 20L,
            parentCommentId = 10L,
        ).single() as CommentFeedItem

        assertTrue(updated.childComments.isEmpty())
        assertTrue(updated.comment.has_replies)
        assertTrue(!updated.repliesLoaded)
    }

    @Test
    fun `expanding replies merges a locally posted reply with the server snapshot`() {
        val viewModel = viewModel(ControllableCommentApi())
        val parent = CommentFeedItem(
            comment = comment(id = 10L, hasReplies = true),
            illustArthurId = 1L,
            childComments = listOf(comment(id = 30L)),
        )
        val items: List<FeedItem> = listOf(parent)

        val updated = viewModel.applyExpandedReplies(
            items = items,
            commentId = 10L,
            children = listOf(comment(id = 20L)),
        ).single() as CommentFeedItem

        assertEquals(listOf(30L, 20L), updated.childComments.map { it.id })
        assertTrue(updated.repliesLoaded)
        assertTrue(updated.comment.has_replies)
    }

    @Test
    fun `successful send invalidates the cached first page of its target`() = runTest {
        val api = ControllableCommentApi()
        val cache = CommentsFirstPageCache()
        val target = CommentTarget(objectId = 1L, objectType = ObjectType.ILLUST)
        val other = CommentTarget(objectId = 2L, objectType = ObjectType.ILLUST)
        cache.put(target, CommentResponse())
        cache.put(other, CommentResponse())
        val viewModel = CommentsComposerViewModel(target = target, api = api.proxy, firstPageCache = cache)
        viewModel.updateDraft("hello")

        val result = async { viewModel.sendComment() }
        runCurrent()
        assertNotNull("发送未落定前不失效", cache.get(target))
        api.complete(comment(id = 20L))
        result.await()

        assertNull(cache.get(target))
        assertNotNull("别的对象的第一页不受影响", cache.get(other))
    }

    @Test
    fun `send answered without a comment still invalidates the cached first page`() = runTest {
        val api = ControllableCommentApi()
        val cache = CommentsFirstPageCache()
        val target = CommentTarget(objectId = 1L, objectType = ObjectType.ILLUST)
        cache.put(target, CommentResponse())
        val viewModel = CommentsComposerViewModel(target = target, api = api.proxy, firstPageCache = cache)
        viewModel.updateDraft("hello")

        supervisorScope {
            val result = async { viewModel.sendComment() }
            runCurrent()
            api.complete(null)
            assertTrue(runCatching { result.await() }.exceptionOrNull() is CommentProtocolException)
        }

        assertNull("服务端已应答，评论多半已落库，缓存不能再信", cache.get(target))
    }

    private fun viewModel(api: ControllableCommentApi) = CommentsComposerViewModel(
        target = CommentTarget(objectId = 1L, objectType = ObjectType.ILLUST),
        api = api.proxy,
    )

    private fun comment(id: Long, hasReplies: Boolean = false) = Comment(
        id = id,
        has_replies = hasReplies,
        user = User(id = id),
    )

    private class ControllableCommentApi : InvocationHandler {
        val proxy: API = Proxy.newProxyInstance(
            API::class.java.classLoader,
            arrayOf(API::class.java),
            this,
        ) as API

        var submissionCount = 0
            private set
        private var continuation: Continuation<PostCommentResponse>? = null

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            if (method.name == "postIllustComment" || method.name == "postNovelComment") {
                submissionCount++
                @Suppress("UNCHECKED_CAST")
                continuation = args?.last() as Continuation<PostCommentResponse>
                return COROUTINE_SUSPENDED
            }
            return when (method.name) {
                "toString" -> "ControllableCommentApi"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> proxy === args?.firstOrNull()
                else -> error("Unexpected API call: ${method.name}")
            }
        }

        fun complete(comment: Comment?) {
            val pending = requireNotNull(continuation) { "No submission is waiting" }
            continuation = null
            pending.resume(PostCommentResponse(comment))
        }
    }
}
