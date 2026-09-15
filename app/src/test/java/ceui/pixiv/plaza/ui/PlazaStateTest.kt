package ceui.pixiv.plaza.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import ceui.pixiv.plaza.*
import ceui.pixiv.shaftapi.MediaObject
import java.io.IOException
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

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
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
    fun `composer limits images restores draft and validates all object kinds`() =
        runTest(dispatcher) {
            val saved = SavedStateHandle()
            val api = FakeApi()
            val vm =
                PlazaComposeViewModel(saved, api, { 42 }, { "Alice" }) { _, _, _ ->
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
                PlazaComposeViewModel(saved, api, { 42 }, { "Alice" }) { _, _, _ ->
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
                PlazaComposeViewModel(saved, api, { 42 }, { "Alice" }) { _, _, onProgress ->
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
    fun `account changes prevent publishing a previous accounts draft`() =
        runTest(dispatcher) {
            var uid = 42L
            val api = FakeApi()
            val vm =
                PlazaComposeViewModel(SavedStateHandle(), api, { uid }, { "Alice" }) { _, _, _ ->
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
            val vm = PlazaTimelineViewModel(SavedStateHandle(), api, { 42 })
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
            val vm = PlazaTimelineViewModel(SavedStateHandle(), api, { 42 })
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

    private inner class FakeApi : PlazaApi {
        val pages = ArrayDeque<CompletableDeferred<PlazaPage>>()
        val creates = mutableListOf<CreatePost>()
        var createFails = false
        var likeFails = false
        var likeGate: CompletableDeferred<PlazaPost>? = null
        var feedCalls = 0
        var likeCalls = 0

        override suspend fun feed(
            before: Long?,
            limit: Int,
            author: Long?,
            replyTo: Long?,
        ): PlazaPage {
            feedCalls++
            return pages.removeFirst().await()
        }

        override suspend fun post(id: Long) = this@PlazaStateTest.post(id)

        override suspend fun create(request: CreatePost): PlazaPost {
            creates += request
            if (createFails) throw IOException("lost")
            return post(10)
        }

        override suspend fun like(id: Long): PlazaPost {
            likeCalls++
            if (likeFails) throw IOException("offline")
            return likeGate?.await() ?: post(id, true)
        }

        override suspend fun unlike(id: Long) = post(id, false)

        override suspend fun react(id: Long, emoji: String) = post(id)

        override suspend fun unreact(id: Long, emoji: String) = post(id)

        override suspend fun delete(id: Long) = DeletePost(true)
    }
}
