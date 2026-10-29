package ceui.pixiv.ui.account

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.UserEntity
import ceui.pixiv.config.RemoteAppConfig
import ceui.pixiv.db.FeedCacheEntity
import ceui.pixiv.db.mirror.BookmarkMirrorEntity
import ceui.pixiv.db.mirror.BookmarkMirrorService
import ceui.pixiv.db.mirror.BookmarkMirrorStateEntity
import ceui.pixiv.db.mirror.BookmarkMirrorTagEntity
import ceui.pixiv.db.mirror.BookmarkShelf
import ceui.pixiv.db.mirror.MirrorContentType
import ceui.pixiv.db.mirror.MirrorPhase
import ceui.pixiv.db.mirror.MirrorRestrict
import ceui.pixiv.services.ServicesProvider
import com.tencent.mmkv.MMKV
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * [AccountDataPurge] 的级联清理行为：账号从本地移除时，**它名下的一切**都必须被清干净，
 * 而且**一个别的 uid 的行都不能被误伤**。
 *
 * ## 为什么这条测试值得存在
 *
 * 这个功能的全部价值就是「不留孤儿数据」，而**孤儿恰恰是看不见的**：删漏了哪个书架、
 * 哪个 MMKV key、哪段 feed 快照，界面上不会有任何症状 —— 账号从列表里消失了，用户以为删干净了，
 * 磁盘上却还躺着几十 MB。它也不会被任何一条点击路径暴露，只会在很久以后以「存储空间怎么少了」
 * 的形式浮出来。
 *
 * 所以这里逐项钉住，且每一项都配一条「另一个 uid 不受影响」的反向断言：清理写宽了（比如把
 * `ownerUid` 的条件漏掉）会误删别人的数据，那是比留孤儿严重得多的事故，同样不会被界面暴露。
 *
 * ## 搭法
 *
 * 跑的是**真的** [AccountDataPurge]：真 Room（内存库）、真 [BookmarkMirrorService]、真
 * [RemoteAppConfig]，只把三处进程级依赖替换掉 ——
 *
 * - `AppDatabase.INSTANCE` 用反射指到内存库（同 `BookmarkMirrorSerialTest`）；
 * - `appServices()` 走 [ContextWrapper] + `ServicesProvider` 动态代理（同
 *   `BookmarkMirrorReconciliationTest`），顺便能断言「清理过程只碰这两个服务」；
 * - MMKV 换成 [MemoryMMKV]，只替换 JNI 存储（同 `TagLegibilityWiringTest`）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    application = Application::class,
    shadows = [AccountDataPurgeTest.MemoryMMKV::class],
    instrumentedPackages = ["com.tencent.mmkv"],
)
class AccountDataPurgeTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var db: AppDatabase
    private lateinit var context: Context
    private lateinit var mirror: BookmarkMirrorService
    private lateinit var remoteConfig: RemoteAppConfig

    /** 要被删掉的账号。 */
    private val target = 111L

    /** 必须原封不动的另一个账号。 */
    private val other = 222L

    @Before
    fun setUp() {
        MemoryMMKV.values.clear()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        ReflectionHelpers.setStaticField(AppDatabase::class.java, "INSTANCE", db)
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", app)

        // 先有 context（它自己就是那个假 ServicesProvider），服务再挂在它上面。
        context = wrapper(services(remoteConfigProvider = { remoteConfig }))
        mirror = BookmarkMirrorService(context)
        remoteConfig = RemoteAppConfig()
    }

    @After
    fun tearDown() {
        // 引擎协程是自己起的，不掐掉会漏到同一个 JVM 的下一条用例里。
        ReflectionHelpers.getField<CoroutineScope>(mirror, "scope").cancel()
        AppDatabase.destroyInstance()
        db.close()
        MemoryMMKV.values.clear()
    }

    // ── 镜像：六个书架的主表 / 标签表 / 状态表 ────────────────────────────

    @Test
    fun `清掉目标 uid 六个书架的镜像行 标签与状态 且不碰另一个 uid`() = runBlocking {
        val dao = db.bookmarkMirrorDao()
        val targetShelves = BookmarkShelf.allOf(target)
        val otherShelves = BookmarkShelf.allOf(other)

        targetShelves.forEach { seedShelf(it, targetId = 1000L) }
        otherShelves.forEach { seedShelf(it, targetId = 2000L) }

        assertEquals(6, targetShelves.size)
        assertEquals(6, targetShelves.sumOf { dao.countOf(it.key) })
        assertEquals(6, otherShelves.sumOf { dao.countOf(it.key) })
        assertEquals(12, dao.allStates().size)

        AccountDataPurge.purgeAccountData(context, target, purgeLocalData = true)

        targetShelves.forEach { shelf ->
            assertEquals("${shelf.label} 的主表应被清空", 0, dao.countOf(shelf.key))
            assertEquals("${shelf.label} 的标签应被清空", 0, tagCountOf(shelf.key))
            assertNull("${shelf.label} 的续传状态应被摘掉", dao.findState(shelf.key))
        }
        otherShelves.forEach { shelf ->
            assertEquals("${shelf.label} 不该被动", 1, dao.countOf(shelf.key))
            assertEquals("${shelf.label} 的标签不该被动", 1, tagCountOf(shelf.key))
            assertNotNull("${shelf.label} 的状态不该被动", dao.findState(shelf.key))
        }
    }

    @Test
    fun `状态表按 ownerUid 整片清 不依赖库里还剩哪些书架`() = runBlocking {
        val dao = db.bookmarkMirrorDao()
        // 用户关掉过某些书架的镜像：状态行不全，但主表里可能还留着历史行。
        // 清理不能靠「反查状态行」来枚举书架，否则这些历史行会全部漏掉。
        val orphaned = BookmarkShelf(target, MirrorContentType.NOVEL, MirrorRestrict.PRIVATE)
        dao.insertRows(listOf(mirrorRow(orphaned, 3000L)))
        dao.insertTags(listOf(tag(orphaned, 3000L)))
        assertNull("前提：这个书架没有状态行", dao.findState(orphaned.key))
        assertEquals(1, dao.countOf(orphaned.key))

        AccountDataPurge.purgeAccountData(context, target, purgeLocalData = true)

        assertEquals("没有状态行的书架也要清", 0, dao.countOf(orphaned.key))
        assertEquals(0, tagCountOf(orphaned.key))
    }

    // ── feeds 首屏快照 ─────────────────────────────────────────────────

    @Test
    fun `清掉目标 uid 的首屏快照 且不碰另一个 uid 与 uid 前缀相同的键`() = runBlocking {
        val dao = db.feedCacheDao()
        val mine = listOf("home#$target#IllustPage", "dynamic#$target#NovelPage")
        val others = listOf("home#$other#IllustPage", "home#${target}0#IllustPage")
        (mine + others).forEach { dao.upsert(feed(it)) }

        AccountDataPurge.purgeAccountData(context, target, purgeLocalData = true)

        mine.forEach { assertNull("$it 应被清掉", dao.find(it)) }
        others.forEach { assertNotNull("$it 不该被动（uid 1110 只是前缀撞上 111）", dao.find(it)) }
    }

    // ── MMKV：私有配置 + 收藏库引导标记 ────────────────────────────────

    @Test
    fun `清掉目标 uid 的四个私有配置 key 且不碰另一个 uid`() = runBlocking {
        val mine = listOf(
            "nana7mi_search_enabled_$target",
            "nana7mi_plan_$target",
            "cloud_translate_enabled_$target",
            "referral_enabled_$target",
        )
        val others = mine.map { it.replace("$target", "$other") }
        (mine + others).forEach { MemoryMMKV.values[it] = true }

        AccountDataPurge.purgeAccountData(context, target, purgeLocalData = true)

        mine.forEach { assertFalse("$it 应被清掉", MemoryMMKV.values.containsKey(it)) }
        others.forEach { assertTrue("$it 不该被动", MemoryMMKV.values.containsKey(it)) }
    }

    @Test
    fun `清掉目标 uid 六个书架的收藏库引导标记 且不碰另一个 uid`() = runBlocking {
        val targetKeys = BookmarkShelf.allOf(target).map { READY_PREFIX + it.key }
        val otherKeys = BookmarkShelf.allOf(other).map { READY_PREFIX + it.key }
        (targetKeys + otherKeys).forEach { MemoryMMKV.values[it] = true }

        AccountDataPurge.purgeAccountData(context, target, purgeLocalData = true)

        targetKeys.forEach { assertFalse("$it 应被清掉", MemoryMMKV.values.containsKey(it)) }
        otherKeys.forEach { assertTrue("$it 不该被动", MemoryMMKV.values.containsKey(it)) }
    }

    // ── user_table ────────────────────────────────────────────────────

    @Test
    fun `删掉目标 uid 的凭证行 且不碰另一个 uid`() = runBlocking {
        db.downloadDao().insertUser(user(target))
        db.downloadDao().insertUser(user(other))

        AccountDataPurge.purgeAccountData(context, target, purgeLocalData = true)

        assertEquals("只该剩下另一个账号", listOf(other), db.downloadDao().allUser.map { it.userID })
    }

    // ── 边界 ──────────────────────────────────────────────────────────

    @Test
    fun `非正 uid 直接返回 什么都不动`() = runBlocking {
        val shelf = BookmarkShelf(0L, MirrorContentType.ILLUST, MirrorRestrict.PUBLIC)
        seedShelf(shelf, targetId = 1L)
        db.downloadDao().insertUser(user(0L))
        MemoryMMKV.values["nana7mi_search_enabled_0"] = true

        AccountDataPurge.purgeAccountData(context, 0L, purgeLocalData = true)

        assertEquals(1, db.bookmarkMirrorDao().countOf(shelf.key))
        assertEquals(1, db.downloadDao().allUser.size)
        assertTrue(MemoryMMKV.values.containsKey("nana7mi_search_enabled_0"))
    }

    /**
     * 清理是**尽力而为**：中间某一步炸了，不该把「删掉凭证」也一起挡下来 —— 否则账号会卡在
     * 列表里，用户点了删除却什么都不发生，而且没有任何提示。
     *
     * 这里让 `remoteAppConfig` 那一步直接抛（模拟 OOM / 初始化失败这类硬故障），
     * 断言最后的 `user_table` 删除照样执行。
     */
    @Test
    fun `中间某一步抛异常不挡住最后的凭证删除`() = runBlocking {
        val broken = wrapper(services(remoteConfigProvider = { error("boom") }))
        db.downloadDao().insertUser(user(target))

        AccountDataPurge.purgeAccountData(broken, target, purgeLocalData = true)

        assertTrue(
            "凭证仍应被删掉，实际剩下 " + db.downloadDao().allUser.map { it.userID },
            db.downloadDao().allUser.none { it.userID == target },
        )
    }

    /**
     * 不勾复选框：**只删凭证行**，镜像 / 标签 / 状态 / 快照 / MMKV 全部原样。
     *
     * 这条顺带钉住「开关真的短路了」：会碰服务与 MMKV 的那几步都经 `appServices()`，而这里的假
     * ServicesProvider 只认清理用得着的那两个 —— 哪天重构把某一步挪到 `if` 外面，
     * 它会立刻炸成红测试，而不是静默多删一份数据。
     */
    @Test
    fun `不勾选复选框时只删凭证 本地残留原样留着`() = runBlocking {
        val dao = db.bookmarkMirrorDao()
        val shelf = BookmarkShelf(target, MirrorContentType.ILLUST, MirrorRestrict.PUBLIC)
        seedShelf(shelf, targetId = 1000L)
        db.feedCacheDao().upsert(feed("home#$target#IllustPage"))
        MemoryMMKV.values["nana7mi_search_enabled_$target"] = true
        MemoryMMKV.values[READY_PREFIX + shelf.key] = true
        db.downloadDao().insertUser(user(target))

        AccountDataPurge.purgeAccountData(context, target, purgeLocalData = false)

        assertTrue(
            "凭证仍要删掉，实际剩下 " + db.downloadDao().allUser.map { it.userID },
            db.downloadDao().allUser.none { it.userID == target },
        )
        assertEquals("镜像行应原样留着", 1, dao.countOf(shelf.key))
        assertEquals("标签应原样留着", 1, tagCountOf(shelf.key))
        assertNotNull("续传状态应原样留着", dao.findState(shelf.key))
        assertNotNull("首屏快照应原样留着", db.feedCacheDao().find("home#$target#IllustPage"))
        assertTrue(
            "私有配置应原样留着",
            MemoryMMKV.values.containsKey("nana7mi_search_enabled_$target"),
        )
        assertTrue("引导标记应原样留着", MemoryMMKV.values.containsKey(READY_PREFIX + shelf.key))
    }

    // ── Java 侧入口（设置页「退出登录」用的那条） ──────────────────────

    /**
     * 回调必须发生在**清理跑完之后**，否则设置页那条链路会在数据还没清干净时就去清会话、
     * 跳登录页 —— 顺序反了就会出现「已退出、清理还在跑」的窗口。
     */
    @Test
    fun `detached 入口在清理跑完之后才回调`() {
        val dao = db.bookmarkMirrorDao()
        val shelf = BookmarkShelf(target, MirrorContentType.ILLUST, MirrorRestrict.PUBLIC)
        seedShelf(shelf, targetId = 1000L)
        db.downloadDao().insertUser(user(target))

        val done = CountDownLatch(1)
        var rowsSeenInCallback = -1
        AccountDataPurge.purgeAccountDataDetached(context, target, purgeLocalData = true) {
            rowsSeenInCallback = dao.countOf(shelf.key)
            done.countDown()
        }

        assertTrue("onFinished 必须被回调", awaitMain(done))
        assertEquals("回调时清理必须已经跑完", 0, rowsSeenInCallback)
        assertTrue(
            "凭证行也该没了",
            db.downloadDao().allUser.none { it.userID == target },
        )
    }

    /**
     * 清理炸了也必须回调。这条是承重的：回调是「退出登录」的续命信号，不发的话用户会停在
     * 「点过退出登录，但什么都没发生」——既没退出、也没有任何提示。
     */
    @Test
    fun `detached 入口在清理失败时也会回调`() {
        val broken = wrapper(services(remoteConfigProvider = { error("boom") }))
        db.downloadDao().insertUser(user(target))

        val done = CountDownLatch(1)
        AccountDataPurge.purgeAccountDataDetached(broken, target, purgeLocalData = true) {
            done.countDown()
        }

        assertTrue("清理失败也必须回调，否则用户被卡在设置页", awaitMain(done))
    }

    /**
     * 回调挂在 `Dispatchers.Main` 上，而 Robolectric 主 looper 默认 PAUSED ——
     * 不自己泵的话它永远不会执行。泵与等待交替：等的时候让出主线程，后台线程才能把
     * 结果投回来。
     */
    private fun awaitMain(latch: CountDownLatch): Boolean {
        val deadline = System.currentTimeMillis() + 15_000L
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (latch.await(20, TimeUnit.MILLISECONDS)) return true
        }
        return false
    }

    // ── 夹具 ──────────────────────────────────────────────────────────

    /**
     * 假的 [ServicesProvider]：只认清理会用到的那两个服务，别的一律炸 —— 这样「清理悄悄多碰了
     * 一个服务」会立刻变成一条红测试，而不是一个没人注意的副作用。
     *
     * `remoteConfigProvider` 传函数而不是实例，是为了让 `setUp` 能在赋值 [remoteConfig] 之前
     * 先把 [context] 建出来。
     */
    private fun services(remoteConfigProvider: () -> RemoteAppConfig): ServicesProvider =
        Proxy.newProxyInstance(
            ServicesProvider::class.java.classLoader, arrayOf(ServicesProvider::class.java),
        ) { proxy, method, _ ->
            when (method.name) {
                "getBookmarkMirror" -> mirror
                "getRemoteAppConfig" -> remoteConfigProvider()
                "toString" -> "FakeServicesProvider"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> false
                else -> error("AccountDataPurge 不该碰 ${method.name}")
            }
        } as ServicesProvider

    /**
     * `appServices()` 读的是 `applicationContext as ServicesProvider`，所以包装器必须把自己
     * 当作 application context 交出去，代理才接得上。
     */
    private fun wrapper(provider: ServicesProvider): Context =
        object : ContextWrapper(app), ServicesProvider by provider {
            override fun getApplicationContext(): Context = this
        }

    private fun seedShelf(shelf: BookmarkShelf, targetId: Long) {
        db.bookmarkMirrorDao().insertRows(listOf(mirrorRow(shelf, targetId)))
        db.bookmarkMirrorDao().insertTags(listOf(tag(shelf, targetId)))
        db.bookmarkMirrorDao().upsertState(state(shelf))
    }

    /** 标签表没有按书架的 count 查询口，直接数一次。 */
    private fun tagCountOf(shelfKey: String): Int =
        db.query(
            SimpleSQLiteQuery(
                "SELECT COUNT(*) FROM bookmark_mirror_tag_table WHERE shelfKey = ?",
                arrayOf<Any>(shelfKey),
            )
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun mirrorRow(shelf: BookmarkShelf, targetId: Long) = BookmarkMirrorEntity(
        shelfKey = shelf.key,
        targetId = targetId,
        ownerUid = shelf.ownerUid,
        contentType = shelf.contentType.code,
        restrictCode = shelf.restrict.code,
        bookmarkSeq = 1L,
        payloadJson = "{}",
        title = "t",
        authorId = 1L,
        authorName = "a",
        workType = "illust",
        pageCount = 1,
        width = 100,
        height = 100,
        aspectRatio = 1f,
        orientation = 3,
        totalBookmarks = 0,
        totalView = 0,
        textLength = 0,
        createDateMs = 0L,
        aiType = 0,
        xRestrict = 0,
        sanityLevel = 0,
        isVisible = true,
        isMuted = false,
        seriesId = 0L,
        tagCount = 1,
        searchText = "t",
        syncedAt = 0L,
        generation = 1,
    )

    private fun tag(shelf: BookmarkShelf, targetId: Long) = BookmarkMirrorTagEntity(
        shelfKey = shelf.key,
        targetId = targetId,
        tagName = "tag",
        displayName = "Tag",
        translatedName = "",
    )

    private fun state(shelf: BookmarkShelf) = BookmarkMirrorStateEntity(
        shelfKey = shelf.key,
        ownerUid = shelf.ownerUid,
        contentType = shelf.contentType.code,
        restrictCode = shelf.restrict.code,
        phase = MirrorPhase.SYNCED,
        nextUrl = null,
        generation = 1,
        nextBackfillSeq = 0L,
        headSeqCursor = 0L,
        headBlockCeiling = 0L,
        pagesThisRun = 0,
        itemsThisRun = 0,
        firstCompletedAt = 1L,
        lastSyncedAt = 0L,
        lastFullSweepAt = 0L,
        lastErrorAt = 0L,
        lastError = null,
        consecutiveFailures = 0,
        cooldownUntil = 0L,
        updatedAt = 0L,
    )

    private fun feed(cacheKey: String) = FeedCacheEntity(
        cacheKey = cacheKey,
        schemaVersion = 1,
        payloadJson = "{}",
        nextCursor = null,
        savedAt = 1L,
    )

    private fun user(uid: Long) = UserEntity().apply {
        userID = uid
        userGson = "{}"
        loginTime = 1L
    }

    companion object {
        /**
         * [ceui.pixiv.db.mirror.BookmarkMirrorReadyBanner] 里那个 private 常量。
         * 这里照抄一份是**故意的**：它变了而这里没跟着变，测试就会红，正好逼人回头对齐。
         */
        private const val READY_PREFIX = "bookmark_mirror_ready_announced_"
    }

    /**
     * 只替换 MMKV 的 JNI 存储，跑的是真实的 `RemoteAppConfig` / `BookmarkMirrorReadyBanner`。
     * 与 `TagLegibilityWiringTest` / `ReaderParagraphSpacingMigrationTest` 里那份同款
     * （该 shadow 目前是各测试类各自持有）。
     *
     * `defaultMMKV` 和 `mmkvWithID` 都要给：前者是 `Shaft.getMMKV()` 走的（收藏库引导标记），
     * 后者是 `RemoteAppConfig` 的独立 namespace 走的（私有配置）。两条都落到同一张
     * [values] 表上，正好一次断言两边。
     */
    @Implements(MMKV::class, isInAndroidSdk = false)
    class MemoryMMKV {
        @Implementation fun count(): Long = values.size.toLong()
        @Implementation fun containsKey(key: String): Boolean = values.containsKey(key)
        @Implementation fun removeValueForKey(key: String) { values.remove(key) }
        @Implementation fun decodeFloat(key: String, defaultValue: Float): Float = values[key] as? Float ?: defaultValue
        @Implementation fun decodeInt(key: String, defaultValue: Int): Int = values[key] as? Int ?: defaultValue
        @Implementation fun decodeBool(key: String, defaultValue: Boolean): Boolean = values[key] as? Boolean ?: defaultValue
        @Implementation fun decodeString(key: String, defaultValue: String?): String? = values[key] as? String ?: defaultValue
        @Implementation fun encode(key: String, value: Float): Boolean {
            values[key] = value
            return true
        }
        @Implementation fun encode(key: String, value: Boolean): Boolean {
            values[key] = value
            return true
        }
        @Implementation fun encode(key: String, value: String): Boolean {
            values[key] = value
            return true
        }
        @Implementation fun encode(key: String, value: Int): Boolean {
            values[key] = value
            return true
        }
        @Implementation fun encode(key: String, value: Long): Boolean {
            values[key] = value
            return true
        }

        companion object {
            val values = mutableMapOf<String, Any>()

            @JvmStatic
            @Implementation
            fun mmkvWithID(id: String): MMKV = newInstance()

            @JvmStatic
            @Implementation
            fun defaultMMKV(): MMKV = newInstance()

            private fun newInstance(): MMKV = ReflectionHelpers.callConstructor(
                MMKV::class.java, ClassParameter.from(Long::class.javaPrimitiveType, 0L),
            )
        }
    }
}