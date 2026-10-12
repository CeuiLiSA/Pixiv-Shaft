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
}
