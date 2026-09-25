package ceui.pixiv.db.mirror

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.MutableLiveData
import androidx.room.Room
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.utils.Settings
import ceui.loxia.User
import ceui.pixiv.api.model.AccountResponse
import ceui.pixiv.api.model.Illust
import ceui.pixiv.api.model.UserPreview
import ceui.pixiv.services.ServicesProvider
import ceui.pixiv.session.SessionManager
import ceui.pixiv.utils.NetworkStateManager
import com.google.gson.Gson
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * 收藏镜像与关注镜像共用同一个引擎：**任何时刻最多一个翻页请求在飞**，一个书架手上的一轮没跑完
 * 不会插进另一个书架的请求，而且两次请求之间的限速间隔跨内容类型共享。
 *
 * 跑的是真引擎（真协程、真 Room、真限速），只把网络换成可观测的假翻页器 —— 它在请求里挂起一段，
 * 正是「如果存在第二条执行路径，它就会趁这时发请求」的那个窗口。限速间隔是真实的 5 秒，
 * 所以这条测试要十来秒。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BookmarkMirrorSerialTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var db: AppDatabase
    private lateinit var account: MutableLiveData<AccountResponse>
    private var previousAccount: AccountResponse? = null

    private val uid = 123L
    private val illustShelf = BookmarkShelf(uid, MirrorContentType.ILLUST, MirrorRestrict.PUBLIC)
    private val userShelf = BookmarkShelf(uid, MirrorContentType.USER, MirrorRestrict.PUBLIC)

    private val inFlight = AtomicInteger()
    private val maxInFlight = AtomicInteger()
    private val requests = Collections.synchronizedList(mutableListOf<Request>())

    private class Request(val shelf: BookmarkShelf, val startedAt: Long, var endedAt: Long = 0L)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        ReflectionHelpers.setStaticField(AppDatabase::class.java, "INSTANCE", db)
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", app)
        Shaft.sSettings = Settings().apply { isBookmarkMirrorEnabled = true }
        Shaft.sGson = Gson()
        account = ReflectionHelpers.getField(SessionManager, "_loggedInAccount")
        previousAccount = account.value
        account.value = AccountResponse(user = User(id = uid))
    }

    @After
    fun tearDown() {
        account.value = previousAccount
        AppDatabase.destroyInstance()
        db.close()
    }

    /** 插画收藏两页、关注一页；每次请求都在「飞行中」挂起 300ms。 */
    private inner class FakeFetcher(override val shelf: BookmarkShelf) : BookmarkShelfFetcher {
        override suspend fun load(nextUrl: String?): FetchedPage {
            val request = Request(shelf, System.currentTimeMillis())
            requests += request
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            try {
                delay(300)
            } finally {
                inFlight.decrementAndGet()
                request.endedAt = System.currentTimeMillis()
            }
            val page = if (nextUrl == null) 1 else 2
            val ids = (1..3).map { page * 100L + it + shelf.contentType.code * 1_000L }
            val items = ids.map { id ->
                MirrorItem(id) { seq, generation, now ->
                    when (shelf.contentType) {
                        MirrorContentType.USER -> BookmarkMirrorMapper.fromUserPreview(
                            shelf, UserPreview(user = User(id = id, name = "u$id")), seq, generation, now,
                        )
                        else -> BookmarkMirrorMapper.fromIllust(shelf, Illust(id = id), seq, generation, now)
                    }
                }
            }
            val next = if (shelf.contentType == MirrorContentType.ILLUST && page == 1) "page-2" else null
            return FetchedPage(items, next)
        }
    }

    private fun syncedState(shelf: BookmarkShelf, now: Long) = BookmarkMirrorStateEntity(
        shelfKey = shelf.key, ownerUid = shelf.ownerUid, contentType = shelf.contentType.code,
        restrictCode = shelf.restrict.code, phase = MirrorPhase.SYNCED, nextUrl = null, generation = 1,
        nextBackfillSeq = 0L, headSeqCursor = 0L, headBlockCeiling = 0L, pagesThisRun = 0, itemsThisRun = 0,
        // 已补齐过、刚维护过、刚重扫过：只有 syncNow 会让它们动起来
        firstCompletedAt = now, lastSyncedAt = now, lastFullSweepAt = now,
        lastErrorAt = 0L, lastError = null, consecutiveFailures = 0, cooldownUntil = 0L, updatedAt = now,
    )

    @Test
    fun `bookmark and following shelves never fetch in parallel and share the rate limit`() {
        val network = NetworkStateManager(app)
        (network.networkState as MutableLiveData).value = NetworkStateManager.NetworkType.WIFI
        val services = Proxy.newProxyInstance(
            ServicesProvider::class.java.classLoader, arrayOf(ServicesProvider::class.java),
        ) { _, method, _ ->
            check(method.name == "getNetworkStateManager")
            network
        } as ServicesProvider
        val context = object : ContextWrapper(app), ServicesProvider by services {
            override fun getApplicationContext(): Context = this
        }
        val now = System.currentTimeMillis()
        db.bookmarkMirrorDao().upsertState(syncedState(illustShelf, now))
        db.bookmarkMirrorDao().upsertState(syncedState(userShelf, now))

        val mirror = BookmarkMirrorService(context) { FakeFetcher(it) }
        try {
            mirror.start()
            mirror.syncNow(illustShelf, "test")
            mirror.syncNow(userShelf, "test")

            val deadline = System.currentTimeMillis() + 30_000L
            while ((requests.size < 3 || inFlight.get() > 0) && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }

            val done = requests.toList()
            assertEquals("三次请求：插画两页 + 关注一页", 3, done.size)
            assertEquals("同一时刻只能有一个请求在飞", 1, maxInFlight.get())

            // 一个书架手上的一轮没跑完，另一个书架插不进来：插画的两页必须相邻
            val order = done.map { it.shelf }
            val illustIndexes = order.indices.filter { order[it] == illustShelf }
            assertEquals(2, illustIndexes.size)
            assertEquals("插画那一轮被关注插队了：$order", 1, illustIndexes[1] - illustIndexes[0])

            // 限速间隔跨书架、跨内容类型共享（5 秒 ±15%）
            done.zipWithNext().forEach { (a, b) ->
                val gap = b.startedAt - a.endedAt
                assertTrue("${a.shelf.label} → ${b.shelf.label} 只隔了 ${gap}ms", gap >= 4_000L)
            }

            assertEquals(6, db.bookmarkMirrorDao().countOf(illustShelf.key))
            assertEquals(3, db.bookmarkMirrorDao().countOf(userShelf.key))
        } finally {
            ReflectionHelpers.getField<CoroutineScope>(mirror, "scope").cancel()
            network.unregisterNetworkCallback()
        }
    }
}
