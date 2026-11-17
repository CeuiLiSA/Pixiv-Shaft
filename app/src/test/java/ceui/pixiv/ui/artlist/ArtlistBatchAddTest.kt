package ceui.pixiv.ui.artlist

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Parcel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import ceui.lisa.activities.Shaft
import ceui.lisa.models.IllustSearchResponse
import ceui.lisa.utils.Settings
import ceui.loxia.ImageUrls
import ceui.loxia.User
import ceui.pixiv.api.API
import ceui.pixiv.api.Client
import ceui.pixiv.api.model.AccountResponse
import ceui.pixiv.api.model.Illust
import ceui.pixiv.api.model.MetaPage
import ceui.pixiv.session.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import retrofit2.Response
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ArtlistBatchAddTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private lateinit var account: MutableLiveData<AccountResponse>
    private var oldAccount: AccountResponse? = null
    private var oldApi: API? = null
    private var oldArtlistApi: ArtlistApi? = null
    private var oldSettings: Settings? = null
    private var oldContext: Context? = null
    private val writes = mutableListOf<Pair<Long, Long>>()
    private val writeTimes = mutableListOf<Long>()
    private var onAdd: (ArtlistAdd) -> Unit = {}
    private var failWork: Long? = null
    private val list = Artlist(7, 123, "漫画分类", "", "", 0, 0, false, null)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        account = ReflectionHelpers.getField(SessionManager, "_loggedInAccount")
        oldAccount = account.value
        account.value = AccountResponse(user = User(id = list.uid))
        oldSettings = Shaft.sSettings
        oldContext = ReflectionHelpers.getStaticField(Shaft::class.java, "sContext")
        Shaft.sSettings = Settings()
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", RuntimeEnvironment.getApplication())
        oldApi = ReflectionHelpers.getField(Client, "_appApi")
        oldArtlistApi = ArtlistRepository.apiOverride
        ReflectionHelpers.setField(Client, "_appApi", Proxy.newProxyInstance(API::class.java.classLoader,
            arrayOf(API::class.java)) { _, method, args ->
            check(method.name == "getIllustByID") { method.name }
            val id = args!![0] as Long
            IllustSearchResponse().apply { illust = manga(id) }
        })
        ArtlistRepository.apiOverride = Proxy.newProxyInstance(ArtlistApi::class.java.classLoader,
            arrayOf(ArtlistApi::class.java)) { _, method, args ->
            check(method.name == "add") { method.name }
            val target = args!![0] as Long
            val body = args[3] as ArtlistAdd
            // The small ID-only navigation must still publish every page of the manga.
            assertEquals(2, body.illust.meta_pages!!.size)
            writes += target to body.illust.id
            writeTimes += dispatcher.scheduler.currentTime
            onAdd(body)
            if (body.illust.id == failWork) Response.error<Artlist>(503, "{}".toResponseBody())
            else Response.success(list.copy(id = target))
        } as ArtlistApi
    }

    @After fun tearDown() {
        models.clear()
        account.value = oldAccount
        ReflectionHelpers.setField(Client, "_appApi", oldApi)
        ArtlistRepository.apiOverride = oldArtlistApi
        Shaft.sSettings = oldSettings
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", oldContext)
        Dispatchers.resetMain()
    }

    private fun manga(id: Long) = Illust(id = id, type = "manga", visible = true, page_count = 2,
        meta_pages = (0..1).map { MetaPage(ImageUrls(original = "https://i.pximg.net/${id}_p$it.jpg")) })

    private fun model(saved: SavedStateHandle = SavedStateHandle(mapOf(
        ArtlistNavigation.WORK_IDS to longArrayOf(1, 2, 3), ArtlistNavigation.SCOPE to "all",
    ))): ArtlistModel = ArtlistModel(saved).also { models.put("model-${models.keys().size}", it) }

    @Test fun `failed batch keeps progress and retries only unfinished works with paced writes`() = runTest(dispatcher) {
        val model = model()
        assertTrue(model.picking)
        assertEquals("mine", model.scope)
        failWork = 2
        model.addTo(list)
        model.addTo(list) // A repeated click must not launch a second batch.
        advanceUntilIdle()
        assertEquals(listOf(7L to 1L, 7L to 2L), writes)
        assertEquals(1, model.batchProgress.value!!.completed)
        assertNotNull(model.batchProgress.value!!.error)
        assertFalse(model.notice.value!!.finish)
        assertFalse(model.busy.value)
        assertTrue(writeTimes[1] - writeTimes[0] >= 1100)

        failWork = null
        model.consumeNotice()
        model.addTo(list)
        advanceUntilIdle()
        assertEquals(listOf(7L to 1L, 7L to 2L, 7L to 2L, 7L to 3L), writes)
        assertEquals(3, model.batchProgress.value!!.completed)
        assertTrue(model.notice.value!!.finish)
    }

    @Test fun `saved selection and partial progress survive recreation and changing target restarts selection`() = runTest(dispatcher) {
        val saved = SavedStateHandle(mapOf(ArtlistNavigation.WORK_IDS to longArrayOf(1, 2, 3)))
        failWork = 2
        model(saved).addTo(list)
        advanceUntilIdle()
        val restored = model(SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        assertEquals(1, restored.batchProgress.value!!.completed)
        failWork = null
        restored.addTo(list)
        advanceUntilIdle()
        assertEquals(listOf(7L to 1L, 7L to 2L, 7L to 2L, 7L to 3L), writes)

        restored.consumeNotice()
        restored.addTo(list.copy(id = 8))
        advanceUntilIdle()
        assertEquals(listOf(8L to 1L, 8L to 2L, 8L to 3L), writes.takeLast(3))
    }

    @Test fun `account change stops the batch and cannot reassign the old selection`() = runTest(dispatcher) {
        val model = model()
        onAdd = { account.value = AccountResponse(user = User(id = 456)) }
        model.addTo(list)
        advanceUntilIdle()
        assertEquals(listOf(7L to 1L), writes)
        assertTrue(model.accountChanged())
        assertEquals(123L, model.account)
        model.addTo(list)
        advanceUntilIdle()
        assertEquals(1, writes.size)
        assertFalse(model.notice.value!!.finish)
    }

    @Test fun `cancellation stops remaining writes without reporting success or swallowing cancellation`() = runTest(dispatcher) {
        val model = model()
        onAdd = { throw CancellationException("closed") }
        model.addTo(list)
        advanceUntilIdle()
        assertEquals(listOf(7L to 1L), writes)
        assertNull(model.notice.value)
        assertNull(model.batchProgress.value!!.error)
        assertFalse(model.busy.value)
    }

    @Test fun `navigation deduplicates IDs and keeps large manga data out of the activity bundle`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val huge = manga(1).copy(meta_pages = List(5000) { manga(1).meta_pages!!.first() })
            ArtlistNavigation.add(activity.get(), listOf(huge, huge, manga(2)))
            val intent = shadowOf(activity.get()).nextStartedActivity
            assertArrayEquals(longArrayOf(1, 2), intent.getLongArrayExtra(ArtlistNavigation.WORK_IDS))
            assertFalse(intent.hasExtra(ArtlistNavigation.WORK))
            val parcel = Parcel.obtain()
            try {
                intent.writeToParcel(parcel, 0)
                assertTrue(parcel.dataSize() < 32_000)
            } finally { parcel.recycle() }
        } finally { activity.pause().stop().destroy() }
    }
}
