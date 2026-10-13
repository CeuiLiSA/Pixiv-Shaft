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
import ceui.pixiv.services.ServicesProvider
import ceui.pixiv.session.SessionManager
import ceui.pixiv.utils.NetworkStateManager
import com.google.gson.Gson
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * 表头滑窗对账（Head Window Reconciliation）单测：验证远端取消收藏后增量维护能精准清理本地镜像。
 *
 * 覆盖：
 * 1. 远端取消置顶项 -> 增量维护精准裁剪孤儿表头 (Top-of-Feed Pruning)；
 * 2. 远端取消区间内部项 -> 增量维护精准闭合空洞 (Window Interval Reconciliation)；
 * 3. 远端清空所有收藏 -> 增量维护清空整架 (Empty Shelf Pruning)；
 * 4. 处于同步在途中的本地并发收藏 -> 受到 ceilingSeq 保护，绝不被误删。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BookmarkMirrorReconciliationTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var db: AppDatabase
    private lateinit var account: MutableLiveData<AccountResponse>
    private var previousAccount: AccountResponse? = null

    private val uid = 456L
    private val illustShelf = BookmarkShelf(uid, MirrorContentType.ILLUST, MirrorRestrict.PUBLIC)

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

    private fun insertRow(shelf: BookmarkShelf, id: Long, seq: Long, generation: Int = 1) {
        val row = BookmarkMirrorMapper.fromIllust(shelf, Illust(id = id, title = "illust-$id"), seq, generation, System.currentTimeMillis())
        db.bookmarkMirrorDao().writePage(listOf(row.row), row.tags)
    }

    private fun syncedState(shelf: BookmarkShelf, now: Long, ceiling: Long = 1_000_000L) = BookmarkMirrorStateEntity(
        shelfKey = shelf.key, ownerUid = shelf.ownerUid, contentType = shelf.contentType.code,
        restrictCode = shelf.restrict.code, phase = MirrorPhase.SYNCED, nextUrl = null, generation = 1,
        nextBackfillSeq = 0L, headSeqCursor = ceiling, headBlockCeiling = ceiling, pagesThisRun = 0, itemsThisRun = 0,
        firstCompletedAt = now, lastSyncedAt = now, lastFullSweepAt = now,
        lastErrorAt = 0L, lastError = null, consecutiveFailures = 0, cooldownUntil = 0L, updatedAt = now,
    )

    @Test
    fun `dao reconcileHeadWindow prunes orphan head above remote top`() {
        val dao = db.bookmarkMirrorDao()
        // 本地已有 3 条：100(置顶 1000), 101(999), 102(998)
        insertRow(illustShelf, 100L, 1000L)
        insertRow(illustShelf, 101L, 999L)
        insertRow(illustShelf, 102L, 998L)
        assertEquals(3, dao.countOf(illustShelf.key))

        // 模拟远端最新首项变为了 101(999)，100 已在 Web 取消
        val removed = dao.reconcileHeadWindow(
            shelfKey = illustShelf.key,
            maxRemoteSeq = 999L,
            minRemoteSeq = 998L,
            ceilingSeq = 2_000_000L,
            retainedIds = listOf(101L, 102L),
        )

        assertEquals("成功删掉孤儿置顶项 100", 1, removed)
        assertEquals(2, dao.countOf(illustShelf.key))
        assertNull("100 已被删除", dao.findRow(illustShelf.key, 100L))
        assertNotNull("101 仍保留", dao.findRow(illustShelf.key, 101L))
        assertNotNull("102 仍保留", dao.findRow(illustShelf.key, 102L))
    }

    @Test
    fun `dao reconcileHeadWindow closes hole in middle of window`() {
        val dao = db.bookmarkMirrorDao()
        // 本地已有 3 条：200(500), 201(499), 202(498)
        insertRow(illustShelf, 200L, 500L)
        insertRow(illustShelf, 201L, 499L)
        insertRow(illustShelf, 202L, 498L)

        // 远端返回 200 与 202，中间的 201 已在 Web 取消
        val removed = dao.reconcileHeadWindow(
            shelfKey = illustShelf.key,
            maxRemoteSeq = 500L,
            minRemoteSeq = 498L,
            ceilingSeq = 1_000_000L,
            retainedIds = listOf(200L, 202L),
        )

        assertEquals("成功闭合中间空洞删除 201", 1, removed)
        assertEquals(2, dao.countOf(illustShelf.key))
        assertNotNull("200 仍保留", dao.findRow(illustShelf.key, 200L))
        assertNull("201 已被删除", dao.findRow(illustShelf.key, 201L))
        assertNotNull("202 仍保留", dao.findRow(illustShelf.key, 202L))
    }

    @Test
    fun `dao reconcileHeadWindow protects concurrent local bookmarks above ceiling`() {
        val dao = db.bookmarkMirrorDao()
        // 本地已有 101(999), 102(998)
        insertRow(illustShelf, 101L, 999L)
        insertRow(illustShelf, 102L, 998L)
        // 并发本地收藏：获得了更高号段的序号 3_000_000L（高于 ceiling 2_000_000L）
        insertRow(illustShelf, 999L, 3_000_000L)

        val removed = dao.reconcileHeadWindow(
            shelfKey = illustShelf.key,
            maxRemoteSeq = 999L,
            minRemoteSeq = 998L,
            ceilingSeq = 2_000_000L,
            retainedIds = listOf(101L, 102L),
        )

        assertEquals("高于 ceilingSeq 的本地并发项受到保护，不被误删", 0, removed)
        assertEquals(3, dao.countOf(illustShelf.key))
        assertNotNull("并发本地项 999 完好无损", dao.findRow(illustShelf.key, 999L))
    }

    @Test
    fun `full maintain loop reconciles web unbookmarked items`() {
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
        db.bookmarkMirrorDao().upsertState(syncedState(illustShelf, now, ceiling = 1_000_000L))

        // 本地已有：100(置顶 1000), 101(999), 102(998)
        insertRow(illustShelf, 100L, 1000L)
        insertRow(illustShelf, 101L, 999L)
        insertRow(illustShelf, 102L, 998L)
        assertEquals(3, db.bookmarkMirrorDao().countOf(illustShelf.key))

        // 模拟远端：100 已被取消，返回 [101, 102]
        val fetcher = object : BookmarkShelfFetcher {
            override val shelf: BookmarkShelf = illustShelf
            override suspend fun load(nextUrl: String?): FetchedPage {
                val items = listOf(101L, 102L).map { id ->
                    MirrorItem(id) { seq, generation, timestamp ->
                        BookmarkMirrorMapper.fromIllust(illustShelf, Illust(id = id), seq, generation, timestamp)
                    }
                }
                return FetchedPage(items, null)
            }
        }

        val mirror = BookmarkMirrorService(context) { fetcher }
        try {
            mirror.start()
            mirror.syncNow(illustShelf, "test_maintain_reconciliation")

            val dao = db.bookmarkMirrorDao()
            val deadline = System.currentTimeMillis() + 15_000L
            while (dao.countOf(illustShelf.key) == 3 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }

            assertEquals("置顶取消项 100 已在增量维护后被删除", 2, dao.countOf(illustShelf.key))
            assertNull("100 不在表中", dao.findRow(illustShelf.key, 100L))
            assertNotNull("101 仍在表中", dao.findRow(illustShelf.key, 101L))
            assertNotNull("102 仍在表中", dao.findRow(illustShelf.key, 102L))
        } finally {
            ReflectionHelpers.getField<CoroutineScope>(mirror, "scope").cancel()
            network.unregisterNetworkCallback()
        }
    }

    @Test
    fun `re-added work carrying its old seq must not widen the window and wipe live rows`() {
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
        db.bookmarkMirrorDao().upsertState(syncedState(illustShelf, now, ceiling = 1_000_000L))

        // 本地镜像 200 条：id k 的序号 = -(k-1)，DESC 即官方收藏顺序。
        for (id in 1..200) insertRow(illustShelf, id.toLong(), -(id - 1).toLong())
        assertEquals(200, db.bookmarkMirrorDao().countOf(illustShelf.key))

        // 远端：151 曾在网页端被取消（旧行位置太深、一直没被清掉），随后又被重新收藏 → 出现在表头。
        // 本轮抓取顺序 = [151, 1, 2, …]；151 沿用旧序号 -150，滑窗因此被拉到 [-150, 0]。
        val fetcher = object : BookmarkShelfFetcher {
            override val shelf: BookmarkShelf = illustShelf

            override suspend fun load(nextUrl: String?): FetchedPage {
                val ids = when (nextUrl) {
                    null -> listOf(151L) + (1L..47L).toList()
                    "cursor-2" -> (48L..95L).toList()
                    else -> emptyList()
                }
                return FetchedPage(
                    items = ids.map { id ->
                        MirrorItem(id) { seq, generation, timestamp ->
                            BookmarkMirrorMapper.fromIllust(
                                illustShelf, Illust(id = id), seq, generation, timestamp,
                            )
                        }
                    },
                    nextUrl = when (nextUrl) {
                        null -> "cursor-2"
                        "cursor-2" -> "cursor-3"
                        else -> null
                    },
                )
            }
        }

        val mirror = BookmarkMirrorService(context) { fetcher }
        try {
            mirror.start()
            mirror.syncNow(illustShelf, "test_re_added_stale_seq")
            awaitMaintain(now)
            val dao = db.bookmarkMirrorDao()

            assertEquals("仍在收藏的行不得被滑窗误删", 200, dao.countOf(illustShelf.key))
            assertNotNull("位置 120 的行仍应在表里", dao.findRow(illustShelf.key, 120L))
        } finally {
            ReflectionHelpers.getField<CoroutineScope>(mirror, "scope").cancel()
            network.unregisterNetworkCallback()
        }
    }

    @Test
    fun `empty remote head needs two consecutive confirmations before clearing the shelf`() {
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
        db.bookmarkMirrorDao().upsertState(syncedState(illustShelf, now, ceiling = 1_000_000L))
        insertRow(illustShelf, 1L, 0L)
        insertRow(illustShelf, 2L, -1L)

        // 远端首页直接为空且没有下一页：可能是一次抽风的空响应，也可能用户真的清空了收藏。
        val fetcher = object : BookmarkShelfFetcher {
            override val shelf: BookmarkShelf = illustShelf
            override suspend fun load(nextUrl: String?): FetchedPage = FetchedPage(emptyList(), null)
        }

        val mirror = BookmarkMirrorService(context) { fetcher }
        try {
            mirror.start()
            val dao = db.bookmarkMirrorDao()

            mirror.syncNow(illustShelf, "test_empty_head_first")
            awaitMaintain(now)
            assertEquals("只观察到一次空，不得清本地镜像", 2, dao.countOf(illustShelf.key))

            val afterFirst = dao.findState(illustShelf.key)!!.lastSyncedAt
            mirror.syncNow(illustShelf, "test_empty_head_second")
            awaitMaintain(afterFirst)
            assertEquals("连续两次确认后才清空", 0, dao.countOf(illustShelf.key))
        } finally {
            ReflectionHelpers.getField<CoroutineScope>(mirror, "scope").cancel()
            network.unregisterNetworkCallback()
        }
    }

    @Test
    fun `inverted seq order in a run skips reconciliation instead of deleting live rows`() {
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
        // 号段已经开过三段：本轮的号段顶是 4_000_000。
        db.bookmarkMirrorDao().upsertState(syncedState(illustShelf, now, ceiling = 3_000_000L))
        // 100 在本轮首页（旧号 1_999_999）；200 是位置更深、仍然收藏着的一行（旧号 2_500_000），
        // 它落在 [100 的旧号, 本轮新号] 这个数值区间里。
        insertRow(illustShelf, 100L, 1_999_999L)
        insertRow(illustShelf, 200L, 2_500_000L)

        // 远端首页 = [100, 999(新收藏)]：999 拿本轮号段顶 4_000_000，却排在 100 后面 → 序号倒挂。
        // 不管倒挂是什么原因造成的，这一轮的滑窗都不再可信，必须整轮放弃对账。
        val fetcher = object : BookmarkShelfFetcher {
            override val shelf: BookmarkShelf = illustShelf
            override suspend fun load(nextUrl: String?): FetchedPage = FetchedPage(
                items = listOf(100L, 999L).map { id ->
                    MirrorItem(id) { seq, generation, timestamp ->
                        BookmarkMirrorMapper.fromIllust(illustShelf, Illust(id = id), seq, generation, timestamp)
                    }
                },
                nextUrl = null,
            )
        }

        val mirror = BookmarkMirrorService(context) { fetcher }
        try {
            mirror.start()
            mirror.syncNow(illustShelf, "test_inverted_seq_order")
            awaitMaintain(now)
            val dao = db.bookmarkMirrorDao()

            assertEquals("序号倒挂时整轮跳过对账，活着的行一条都不能少", 3, dao.countOf(illustShelf.key))
            assertNotNull("位置更深的 200 必须还在", dao.findRow(illustShelf.key, 200L))
        } finally {
            ReflectionHelpers.getField<CoroutineScope>(mirror, "scope").cancel()
            network.unregisterNetworkCallback()
        }
    }

    /** 等到这一轮增量维护收尾（onRunFinished 会把 lastSyncedAt 推到新值）。 */
    private fun awaitMaintain(previousSyncedAt: Long) {
        val dao = db.bookmarkMirrorDao()
        val deadline = System.currentTimeMillis() + 20_000L
        while (dao.findState(illustShelf.key)?.lastSyncedAt == previousSyncedAt &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(50)
        }
    }
}
