package ceui.pixiv.feeds

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FeedViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private data class Row(val id: Int, val text: String = "") : FeedItem {
        override val feedKey: Any get() = id
    }

    /** 页码游标的假数据源：pages[cursor] 为一页，翻到最后一页 nextCursor 为 null。 */
    private class FakeSource(
        private val pages: List<List<Row>>,
        var failOn: Int? = null,
    ) : FeedSource<Int> {
        var loadCount = 0

        override suspend fun load(cursor: Int?): FeedPage<Int> {
            loadCount++
            val index = cursor ?: 0
            if (failOn == index) throw RuntimeException("boom at page $index")
            val next = (index + 1).takeIf { it < pages.size }
            return FeedPage(pages[index], next)
        }
    }

    /** 带翻页策略的假数据源：页数据同 [FakeSource]，策略由测试指定。 */
    private class PolicySource(
        pages: List<List<Row>>,
        private val policy: FeedPagingPolicy,
    ) : FeedSource<Int> {
        private val inner = FakeSource(pages)
        val loadCount: Int get() = inner.loadCount
        override suspend fun load(cursor: Int?): FeedPage<Int> = inner.load(cursor)
        override fun pagingPolicy(): FeedPagingPolicy = policy
    }

    /** 每页一条、页号即 id 的 n 页。 */
    private fun onePerPage(n: Int): List<List<Row>> = List(n) { listOf(Row(it)) }

    private val noThrottle = FeedPagingPolicy(maxAutoPages = Int.MAX_VALUE, minPageIntervalMs = 0)

    @Test
    fun `refresh loads first page`() = runTest(dispatcher) {
        val vm = FeedViewModel(FakeSource(listOf(listOf(Row(1), Row(2)), listOf(Row(3)))))
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf(Row(1), Row(2)), state.items)
        assertEquals(LoadState.Idle, state.refresh)
        assertTrue(state.hasLoadedOnce)
        assertFalse(state.reachedEnd)
    }

    @Test
    fun `loadMore appends dedups and reaches end`() = runTest(dispatcher) {
        // 第二页和首页有重叠（翻页窗口漂移），Row(2) 不应重复出现
        val vm = FeedViewModel(FakeSource(listOf(listOf(Row(1), Row(2)), listOf(Row(2), Row(3)))))
        advanceUntilIdle()

        vm.loadMore()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf(Row(1), Row(2), Row(3)), state.items)
        assertTrue(state.reachedEnd)

        // 到底之后 loadMore 是 no-op
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(3, vm.uiState.value.items.size)
    }

    @Test
    fun `loadMore is single-flight even before the first job runs`() = runTest(dispatcher) {
        val source = FakeSource(listOf(listOf(Row(1)), listOf(Row(2)), listOf(Row(3))))
        val vm = FeedViewModel(source)
        advanceUntilIdle()
        val countAfterRefresh = source.loadCount

        // 同步连调两次：StandardTestDispatcher（非 immediate）下第一个 job 还没跑到
        // append=Loading 提交，状态快照守卫看不见它——必须由 appendJob.isActive 挡住第二发，
        // 否则会放进两个 job 重复拉同一页（onNearEnd 在一次布局里连触即此形态）
        vm.loadMore()
        vm.loadMore()
        advanceUntilIdle()

        assertEquals(countAfterRefresh + 1, source.loadCount)
        assertEquals(listOf(Row(1), Row(2)), vm.uiState.value.items)
    }

    @Test
    fun `append error stops auto load until retry`() = runTest(dispatcher) {
        val source = FakeSource(listOf(listOf(Row(1)), listOf(Row(2))), failOn = 1)
        val vm = FeedViewModel(source)
        advanceUntilIdle()

        vm.loadMore()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.append is LoadState.Error)

        // 出错后滚动继续触发 loadMore 不应发请求
        val countAfterError = source.loadCount
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(countAfterError, source.loadCount)

        // 用户点重试后恢复
        source.failOn = null
        vm.retryAppend()
        advanceUntilIdle()
        assertEquals(listOf(Row(1), Row(2)), vm.uiState.value.items)
        assertEquals(LoadState.Idle, vm.uiState.value.append)
    }

    @Test
    fun `first load error then retry recovers`() = runTest(dispatcher) {
        val source = FakeSource(listOf(listOf(Row(1))), failOn = 0)
        val vm = FeedViewModel(source)
        advanceUntilIdle()

        val errored = vm.uiState.value
        assertTrue(errored.refresh is LoadState.Error)
        assertTrue(errored.showFullscreenError)
        assertFalse(errored.hasLoadedOnce)

        source.failOn = null
        vm.refresh()
        advanceUntilIdle()
        assertEquals(listOf(Row(1)), vm.uiState.value.items)
    }

    @Test
    fun `refresh wins over in-flight append`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val slowSecondPage = object : FeedSource<Int> {
            override suspend fun load(cursor: Int?): FeedPage<Int> {
                if (cursor == null) return FeedPage(listOf(Row(1)), 1)
                gate.await() // 第二页挂起，模拟慢请求
                return FeedPage(listOf(Row(99)), null)
            }
        }
        val vm = FeedViewModel(slowSecondPage)
        advanceUntilIdle()

        vm.loadMore() // 挂在 gate 上
        dispatcher.scheduler.runCurrent()
        vm.refresh() // 应取消 append
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf(Row(1)), state.items) // Row(99) 被取消，没进列表
        assertEquals(LoadState.Idle, state.append)
    }

    @Test
    fun `loadMore chases past pages with zero new items`() = runTest(dispatcher) {
        // 页1 后跟一整页重复（服务端翻页窗口漂移/整页被过滤的形态），再跟真正的新页
        val source = FakeSource(
            listOf(
                listOf(Row(1), Row(2)),
                listOf(Row(1), Row(2)),
                listOf(Row(3)),
            )
        )
        val vm = FeedViewModel(source)
        advanceUntilIdle()

        vm.loadMore()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf(Row(1), Row(2), Row(3)), state.items)
        assertTrue(state.reachedEnd)
        assertEquals(LoadState.Idle, state.append)
    }

    @Test
    fun `loadMore gives up after hop cap and marks end`() = runTest(dispatcher) {
        // 首页之后全是重复页且游标不断，追载必须有限步内收敛
        val source = FakeSource(List(12) { listOf(Row(1)) })
        val vm = FeedViewModel(source)
        advanceUntilIdle()
        val countAfterRefresh = source.loadCount

        vm.loadMore()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf(Row(1)), state.items)
        assertTrue(state.reachedEnd)
        assertEquals(LoadState.Idle, state.append)
        // 追载止步于当前上限（2 跳），不会翻完全部 12 页
        assertEquals(countAfterRefresh + 2, source.loadCount)
    }

    @Test
    fun `refresh chases past fully-filtered first pages`() = runTest(dispatcher) {
        // 首页整页被 mapper 滤空（#729 场景），refresh 必须继续向后翻到有内容
        val vm = FeedViewModel(
            FakeSource(listOf(emptyList(), emptyList(), listOf(Row(1))))
        )
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf(Row(1)), state.items)
        assertTrue(state.reachedEnd)
        assertTrue(state.hasLoadedOnce)
        assertEquals(LoadState.Idle, state.refresh)
    }

    @Test
    fun `refresh marks end when every page is empty within hop cap`() = runTest(dispatcher) {
        val source = FakeSource(List(12) { emptyList<Row>() })
        val vm = FeedViewModel(source)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.items.isEmpty())
        assertTrue(state.reachedEnd)
        assertTrue(state.showEmptyState)
        // 首页 + 2 跳追载后收手
        assertEquals(3, source.loadCount)
    }

    @Test
    fun `failed refresh does not leak new-generation cursor onto old items`() = runTest(dispatcher) {
        // 刷新的新一代首页整页被滤空（cursor=100 是新一代游标），hop 追载请求失败：
        // 屏幕上还是旧一代列表，游标必须停在旧一代（1），不能被推进到新一代（100）
        var failRefresh = false
        val source = object : FeedSource<Int> {
            override suspend fun load(cursor: Int?): FeedPage<Int> = when (cursor) {
                null -> if (failRefresh) FeedPage(emptyList(), 100) else FeedPage(listOf(Row(1)), 1)
                1 -> FeedPage(listOf(Row(2)), null) // 旧一代第 2 页
                else -> throw RuntimeException("boom at $cursor") // 新一代游标(100)的请求全部失败
            }
        }
        val vm = FeedViewModel(source)
        advanceUntilIdle()
        assertEquals(listOf(Row(1)), vm.uiState.value.items)

        failRefresh = true
        vm.refresh()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.refresh is LoadState.Error)
        assertEquals(listOf(Row(1)), vm.uiState.value.items)

        // 继续翻页必须走旧一代游标拿 Row(2)，而不是新一代的 Row(99) 混进旧列表
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(Row(1), Row(2)), vm.uiState.value.items)
        assertTrue(vm.uiState.value.reachedEnd)
    }

    @Test
    fun `adoptCursor cancels in-flight append so stale cursor cannot clobber handover`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val source = object : FeedSource<Int> {
            override suspend fun load(cursor: Int?): FeedPage<Int> {
                if (cursor == null) return FeedPage(listOf(Row(1)), 1)
                if (cursor == 1) {
                    gate.await() // 旧游标的追加挂起在网络上
                    return FeedPage(listOf(Row(2)), 2)
                }
                return FeedPage(listOf(Row(99)), null)
            }
        }
        val vm = FeedViewModel(source)
        advanceUntilIdle()

        vm.loadMore() // 基于游标 1，挂起
        dispatcher.scheduler.runCurrent()
        vm.adoptCursor(5) // 详情页已替列表翻到游标 5
        gate.complete(Unit) // 旧追加此刻恢复，必须已被取消
        advanceUntilIdle()

        assertEquals(LoadState.Idle, vm.uiState.value.append)
        assertEquals(listOf(Row(1)), vm.uiState.value.items) // Row(2) 未混入

        vm.loadMore() // 应从交接来的游标 5 继续
        advanceUntilIdle()
        assertEquals(listOf(Row(1), Row(99)), vm.uiState.value.items)
        assertTrue(vm.uiState.value.reachedEnd)
    }

    @Test
    fun `adoptCursor hands over external pagination progress`() = runTest(dispatcher) {
        val vm = FeedViewModel(FakeSource(listOf(listOf(Row(1)), listOf(Row(2)), listOf(Row(3)))))
        advanceUntilIdle()

        // 外部组件（详情页 pager）已经替列表翻掉了第 2 页
        vm.adoptCursor(2)
        vm.loadMore()
        advanceUntilIdle()

        assertEquals(listOf(Row(1), Row(3)), vm.uiState.value.items)
        assertTrue(vm.uiState.value.reachedEnd)
    }

    @Test
    fun `appendItems dedups against list and within batch without touching source`() = runTest(dispatcher) {
        val source = FakeSource(listOf(listOf(Row(1), Row(2))))
        val vm = FeedViewModel(source)
        advanceUntilIdle()
        val countAfterRefresh = source.loadCount

        // Row(2) 与列表重复、Row(3) 在入参内部重复，都只保留一份；且不发任何网络请求
        vm.appendItems(listOf(Row(2), Row(3), Row(3), Row(4)))

        assertEquals(listOf(Row(1), Row(2), Row(3), Row(4)), vm.uiState.value.items)
        assertEquals(countAfterRefresh, source.loadCount)

        // 全部重复 / 空入参都是 no-op
        vm.appendItems(listOf(Row(1), Row(4)))
        vm.appendItems(emptyList())
        assertEquals(4, vm.uiState.value.items.size)
    }

    @Test
    fun `loadMore with zero new items keeps the items list instance`() = runTest(dispatcher) {
        // 整页重复时不该造等价新列表：下游按 identity 跳过的扫描（合池等）依赖实例不变
        val vm = FeedViewModel(FakeSource(listOf(listOf(Row(1)), listOf(Row(1)))))
        advanceUntilIdle()
        val before = vm.uiState.value.items

        vm.loadMore()
        advanceUntilIdle()

        assertSame(before, vm.uiState.value.items)
        assertTrue(vm.uiState.value.reachedEnd)
    }

    @Test
    fun `shouldNotifyRefreshError fires once per error instance`() = runTest(dispatcher) {
        val vm = FeedViewModel(FakeSource(listOf(listOf(Row(1)))))
        advanceUntilIdle()

        val error = LoadState.Error(RuntimeException("boom"))
        assertTrue(vm.shouldNotifyRefreshError(error))
        // 视图重建（旋转）后重新 render 同一份状态，不该重复提示
        assertFalse(vm.shouldNotifyRefreshError(error))
        // 新一次失败是新 Error 实例，恢复提示
        assertTrue(vm.shouldNotifyRefreshError(LoadState.Error(RuntimeException("boom2"))))
    }

    @Test
    fun `updateItems mutates in place`() = runTest(dispatcher) {
        val vm = FeedViewModel(FakeSource(listOf(listOf(Row(1, "a"), Row(2, "b")))))
        advanceUntilIdle()

        vm.updateItems(Row::class.java) { row ->
            if (row.id == 2) row.copy(text = "liked") else row
        }
        assertEquals(listOf(Row(1, "a"), Row(2, "liked")), vm.uiState.value.items)

        vm.removeItems { it.feedKey == 1 }
        assertEquals(listOf(Row(2, "liked")), vm.uiState.value.items)
    }

    @Test
    fun `adoptCursorAndMutateItems commits cursor and structural edit together`() = runTest(dispatcher) {
        val vm = FeedViewModel(FakeSource(listOf(listOf(Row(1, "loading")))))
        advanceUntilIdle()
        val versionBefore = vm.uiState.value.structureVersion

        vm.adoptCursorAndMutateItems(cursor = 7) { rows ->
            rows.map { (it as Row).copy(text = "loaded") } + Row(2, "related")
        }

        val state = vm.uiState.value
        assertEquals(listOf(Row(1, "loaded"), Row(2, "related")), state.items)
        assertFalse("新游标应重新开启分页", state.reachedEnd)
        assertEquals("旧前缀条目被替换，必须推进结构版本", versionBefore + 1, state.structureVersion)
        assertEquals(7, vm.currentCursor)
    }

    /**
     * 零变化的编辑必须真的免费：不换 items 实例、不推进 structureVersion。
     *
     * 否则 no-op 也会造出等价新列表 → 一轮全量 diff + 让 structureVersion 的增量消费方
     *（IllustFeedPoolSync）全表重扫。这不是理论情形：本页自己发起的收藏会经 LIKED_* 广播
     * 绕回自己，而 `withBookmarked` / `withFollowed` 在已是目标态时原样返回 —— 每次点赞
     * 都会撞上这条路径。
     */
    @Test
    fun `no-op updateItems keeps the list instance and structureVersion`() = runTest(dispatcher) {
        val vm = FeedViewModel(FakeSource(listOf(listOf(Row(1, "a"), Row(2, "b")))))
        advanceUntilIdle()

        val itemsBefore = vm.uiState.value.items
        val versionBefore = vm.uiState.value.structureVersion

        // transform 原样返回（幂等 withBookmarked 的形状）
        vm.updateItems(Row::class.java) { it }
        assertSame("零变化不该换 items 实例", itemsBefore, vm.uiState.value.items)
        assertEquals("零变化不该推进 structureVersion", versionBefore, vm.uiState.value.structureVersion)

        // 谓词没命中任何条目的删除，同样免费
        vm.removeItems { it.feedKey == 999 }
        assertSame(itemsBefore, vm.uiState.value.items)
        assertEquals(versionBefore, vm.uiState.value.structureVersion)

        // 对照：真的改了才推进
        vm.updateItems(Row::class.java) { row -> if (row.id == 2) row.copy(text = "liked") else row }
        assertEquals(versionBefore + 1, vm.uiState.value.structureVersion)
    }

    /**
     * refreshGeneration 只认「整代替换」：refresh 的每次整代提交 +1，其余编辑一律不动。
     *
     * 这是 FeedFragment 绕开跨代 diff 的唯一信号。**不能拿 structureVersion 顶替**——点赞/删除
     * 也会推进 structureVersion，用它当整代信号会让每次点赞都把列表清表回顶。
     */
    @Test
    fun `refreshGeneration only advances on whole-generation commits`() = runTest(dispatcher) {
        val vm = FeedViewModel(
            FakeSource(listOf(listOf(Row(1, "a"), Row(2, "b")), listOf(Row(3, "c"))))
        )
        advanceUntilIdle()
        val genAfterFirstLoad = vm.uiState.value.refreshGeneration
        assertEquals("首屏是一次整代提交", 1, genAfterFirstLoad)

        // 翻页：纯尾部追加,不是换代
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(genAfterFirstLoad, vm.uiState.value.refreshGeneration)

        // 点赞/就地改：structureVersion 会动,代号不能动
        val versionBefore = vm.uiState.value.structureVersion
        vm.updateItems(Row::class.java) { row -> if (row.id == 1) row.copy(text = "liked") else row }
        assertEquals(versionBefore + 1, vm.uiState.value.structureVersion)
        assertEquals(
            "点赞不是换代——推进了代号会让列表被清表回顶",
            genAfterFirstLoad, vm.uiState.value.refreshGeneration,
        )

        // 删除一条：同上
        vm.removeItems { it.feedKey == 2 }
        assertEquals(genAfterFirstLoad, vm.uiState.value.refreshGeneration)

        // 追加外部条目(详情页续拉回传)：同上
        vm.appendItems(listOf(Row(99, "z")))
        assertEquals(genAfterFirstLoad, vm.uiState.value.refreshGeneration)

        // 下拉刷新：换代
        vm.refresh()
        advanceUntilIdle()
        assertEquals(genAfterFirstLoad + 1, vm.uiState.value.refreshGeneration)
    }

    // ── 本地优先（FeedSource.loadFromCache）──────────────────────────────────

    /**
     * 可缓存的假数据源：[cachedPage] 是磁盘快照（null=未命中），网络首屏可选挂在 [networkGate]
     * 上模拟慢请求 / 观察「缓存已显示、刷新在飞」的中间态，[failNetworkRefresh] 模拟离线刷新失败。
     */
    private class FakeCacheableSource(
        private val networkPages: List<List<Row>>,
        var cachedPage: FeedPage<Int>? = null,
        var failNetworkRefresh: Boolean = false,
        private val networkGate: CompletableDeferred<Unit>? = null,
        /** 模拟「启动时自动刷新」设置项（issue #955）：false=命中快照就停在快照上。 */
        private val autoRefreshAfterCacheHit: Boolean = true,
    ) : FeedSource<Int> {
        var loadFromCacheCount = 0
        var firstPageNetworkCount = 0

        override fun refreshAfterCacheHit(): Boolean = autoRefreshAfterCacheHit

        override suspend fun loadFromCache(): FeedPage<Int>? {
            loadFromCacheCount++
            return cachedPage
        }

        override suspend fun load(cursor: Int?): FeedPage<Int> {
            if (cursor == null) {
                firstPageNetworkCount++
                networkGate?.await()
                if (failNetworkRefresh) throw RuntimeException("network boom")
            }
            val index = cursor ?: 0
            val next = (index + 1).takeIf { it < networkPages.size }
            return FeedPage(networkPages[index], next)
        }
    }

    @Test
    fun `cold start shows cache first then swaps to fresh network`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val source = FakeCacheableSource(
            networkPages = listOf(listOf(Row(10), Row(11)), listOf(Row(12))),
            cachedPage = FeedPage(listOf(Row(1), Row(2)), 1),
            networkGate = gate,
        )
        val vm = FeedViewModel(source)
        advanceUntilIdle()

        // 网络首屏还挂在 gate 上：屏幕先显示磁盘缓存，顶部转圈刷新中（内容态而非全屏 loading）
        val cacheShown = vm.uiState.value
        assertEquals(listOf(Row(1), Row(2)), cacheShown.items)
        assertTrue(cacheShown.showingCache)
        assertTrue(cacheShown.hasLoadedOnce)
        assertTrue(cacheShown.refresh is LoadState.Loading)
        assertFalse(cacheShown.showFullscreenLoading)
        assertEquals(1, source.loadFromCacheCount)

        gate.complete(Unit)
        advanceUntilIdle()

        // 网络回来覆盖成最新数据，缓存标记清除
        val fresh = vm.uiState.value
        assertEquals(listOf(Row(10), Row(11)), fresh.items)
        assertFalse(fresh.showingCache)
        assertEquals(LoadState.Idle, fresh.refresh)
    }

    @Test
    fun `cold start without cache goes straight to fullscreen loading`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val source = FakeCacheableSource(
            networkPages = listOf(listOf(Row(10))),
            cachedPage = null,
            networkGate = gate,
        )
        val vm = FeedViewModel(source)
        advanceUntilIdle()

        val loading = vm.uiState.value
        assertTrue(loading.items.isEmpty())
        assertFalse(loading.showingCache)
        assertTrue(loading.showFullscreenLoading) // 没缓存就是常规全屏 loading
        assertEquals(1, source.loadFromCacheCount)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(Row(10)), vm.uiState.value.items)
    }

    @Test
    fun `pull to refresh after loaded does not re-read cache`() = runTest(dispatcher) {
        val source = FakeCacheableSource(
            networkPages = listOf(listOf(Row(10))),
            cachedPage = FeedPage(listOf(Row(1)), null),
        )
        val vm = FeedViewModel(source)
        advanceUntilIdle()
        assertEquals(listOf(Row(10)), vm.uiState.value.items)
        val cacheReadsAfterColdStart = source.loadFromCacheCount

        // 已加载过（hasLoadedOnce=true）：下拉刷新不再读缓存，不会闪回旧数据
        vm.refresh()
        advanceUntilIdle()
        assertEquals(cacheReadsAfterColdStart, source.loadFromCacheCount)
        assertFalse(vm.uiState.value.showingCache)
    }

    @Test
    fun `network failure after cache keeps cache visible without fullscreen error`() = runTest(dispatcher) {
        val source = FakeCacheableSource(
            networkPages = listOf(listOf(Row(10))),
            cachedPage = FeedPage(listOf(Row(1), Row(2)), 1),
            failNetworkRefresh = true,
        )
        val vm = FeedViewModel(source)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf(Row(1), Row(2)), state.items) // 离线也留着上次首屏
        assertTrue(state.refresh is LoadState.Error)
        assertFalse(state.showFullscreenError) // 有内容 → 不顶成全屏错误
        assertTrue(state.hasLoadedOnce)
        assertFalse(state.showingCache) // 刷新停在 Error 就不再是「更新中」，showingCache 归 false
    }

    @Test
    fun `itemsFromCache marks only the cache generation`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val source = FakeCacheableSource(
            networkPages = listOf(listOf(Row(10), Row(11))),
            cachedPage = FeedPage(listOf(Row(1), Row(2)), 1),
            networkGate = gate,
        )
        val vm = FeedViewModel(source)
        advanceUntilIdle()

        // 缓存那一代：条目来自磁盘快照。副作用消费方（IllustFeedPoolSync 喂 ObjectPool / 关注态）
        // 靠这个字段整代跳过——重放旧快照会把用户刚点的收藏 / 关注盖回去。
        assertTrue(vm.uiState.value.itemsFromCache)

        gate.complete(Unit)
        advanceUntilIdle()
        // 网络那一代是真正下行的新鲜数据，门放开
        assertFalse(vm.uiState.value.itemsFromCache)
    }

    @Test
    fun `refresh failure keeps itemsFromCache so stale beans stay gated`() = runTest(dispatcher) {
        // 回归测：itemsFromCache 曾是 showingCache 那个存储字段，刷新失败时被手动归零，而 items
        // 仍是快照那一代 —— 消费方于是把陈旧 bean 当新鲜数据放行，恰好在离线（本地优先最该起作用
        // 的场景）把收藏 / 关注态盖回去。「这一代来自哪」是事实（itemsFromCache），「是否更新中」
        // 是结论（showingCache）：刷新停在 Error 只该让后者变 false。
        val source = FakeCacheableSource(
            networkPages = listOf(listOf(Row(10))),
            cachedPage = FeedPage(listOf(Row(1), Row(2)), 1),
            failNetworkRefresh = true,
        )
        val vm = FeedViewModel(source)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf(Row(1), Row(2)), state.items) // 屏幕上这一代仍是快照
        assertTrue(state.refresh is LoadState.Error)
        assertTrue(state.itemsFromCache) // 事实不变：这批数据依旧来自磁盘
        assertFalse(state.showingCache) // 结论变了：不再是「更新中」

        // 网络恢复后下拉刷新：新一代是网络数据，门放开
        source.failNetworkRefresh = false
        vm.refresh()
        advanceUntilIdle()
        assertEquals(listOf(Row(10)), vm.uiState.value.items)
        assertFalse(vm.uiState.value.itemsFromCache)
    }

    @Test
    fun `cache restore failure falls through to network without crashing`() = runTest(dispatcher) {
        // loadFromCache（跑用户 mapper）在恢复态抛错，绝不能崩：必须被吞、照常走网络首屏
        val source = object : FeedSource<Int> {
            override suspend fun loadFromCache(): FeedPage<Int>? {
                throw RuntimeException("restore boom")
            }

            override suspend fun load(cursor: Int?): FeedPage<Int> {
                val index = cursor ?: 0
                return FeedPage(listOf(Row(10 + index)), null)
            }
        }
        val vm = FeedViewModel(source)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf(Row(10)), state.items) // 网络首屏正常出
        assertEquals(LoadState.Idle, state.refresh)
        assertTrue(state.hasLoadedOnce)
        assertFalse(state.showingCache)
    }

    @Test
    fun `loadMore is suppressed while cache shown and refresh in flight`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val source = FakeCacheableSource(
            networkPages = listOf(listOf(Row(10), Row(11)), listOf(Row(12))),
            cachedPage = FeedPage(listOf(Row(1), Row(2)), 1),
            networkGate = gate,
        )
        val vm = FeedViewModel(source)
        advanceUntilIdle()

        // 缓存展示中、网络刷新在飞：翻页被 `refresh is Loading` 守卫挡住（不拿旧游标翻页）
        assertTrue(vm.uiState.value.showingCache)
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(Row(1), Row(2)), vm.uiState.value.items)

        // 刷新落地后翻页恢复正常，走新一代游标
        gate.complete(Unit)
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(Row(10), Row(11), Row(12)), vm.uiState.value.items)
        assertTrue(vm.uiState.value.reachedEnd)
    }

    // ── 启动时不自动刷新（FeedSource.refreshAfterCacheHit，issue #955）────────────

    @Test
    fun `cache hit stays on snapshot when auto refresh is off`() = runTest(dispatcher) {
        val source = FakeCacheableSource(
            networkPages = listOf(listOf(Row(10), Row(11)), listOf(Row(12))),
            cachedPage = FeedPage(listOf(Row(1), Row(2)), 1),
            autoRefreshAfterCacheHit = false,
        )
        val vm = FeedViewModel(source)
        advanceUntilIdle()

        // 冷启停在磁盘快照上：一次网络首屏都没发，也不留「刷新中」的尾巴
        val state = vm.uiState.value
        assertEquals(listOf(Row(1), Row(2)), state.items)
        assertEquals(0, source.firstPageNetworkCount)
        assertEquals(LoadState.Idle, state.refresh)
        assertTrue(state.hasLoadedOnce)   // ColdStartSplashGate 靠它放行开屏动画
        assertFalse(state.showFullscreenLoading)
        assertFalse(state.showEmptyState)
        // 这一代仍然来自磁盘：副作用消费方靠它门控，不能因为「不刷新了」就抹掉
        assertTrue(state.itemsFromCache)
        assertFalse(state.showingCache)   // 没有网络刷新在飞，不该显示「更新中」

        // 快照的游标照常能往后翻
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(Row(1), Row(2), Row(12)), vm.uiState.value.items)

        // 用户下拉刷新才换新一批
        vm.refresh()
        advanceUntilIdle()
        assertEquals(1, source.firstPageNetworkCount)
        assertEquals(listOf(Row(10), Row(11)), vm.uiState.value.items)
        assertFalse(vm.uiState.value.itemsFromCache)
    }

    @Test
    fun `auto refresh off still loads network when cache misses`() = runTest(dispatcher) {
        // 没有快照就没有内容可停留，开关不该把首屏一起关掉（否则冷启是张空白页）
        val source = FakeCacheableSource(
            networkPages = listOf(listOf(Row(10))),
            cachedPage = null,
            autoRefreshAfterCacheHit = false,
        )
        val vm = FeedViewModel(source)
        advanceUntilIdle()

        assertEquals(1, source.firstPageNetworkCount)
        assertEquals(listOf(Row(10)), vm.uiState.value.items)
        assertEquals(LoadState.Idle, vm.uiState.value.refresh)
    }

    // ── 翻页节奏与预算（FeedPagingPolicy）──

    @Test
    fun `default paging policy is thirty pages one second apart and is what a plain source reports`() {
        assertEquals(
            FeedPagingPolicy(maxAutoPages = 30, minPageIntervalMs = 1_000L, burstIdleResetMs = 5_000L),
            FeedPagingPolicy.Default,
        )
        assertEquals(FeedPagingPolicy.Default, FeedSource<Int> { FeedPage(emptyList(), null) }.pagingPolicy())
        assertEquals(Int.MAX_VALUE, FeedPagingPolicy.Unlimited.maxAutoPages)
        assertEquals(0L, FeedPagingPolicy.Unlimited.minPageIntervalMs)
        // 预算 0 不是「无上限」而是配置错误：会让第一页之后永远停手
        assertThrows(IllegalArgumentException::class.java) { FeedPagingPolicy(maxAutoPages = 0) }
        assertThrows(IllegalArgumentException::class.java) { FeedPagingPolicy(minPageIntervalMs = -1) }
        assertThrows(IllegalArgumentException::class.java) { FeedPagingPolicy(burstIdleResetMs = -1) }
    }

    @Test
    fun `auto paging pauses after the budget and continueAppend grants another`() = runTest(dispatcher) {
        val source = PolicySource(onePerPage(10), FeedPagingPolicy(maxAutoPages = 3, minPageIntervalMs = 0))
        val vm = FeedViewModel(source)
        advanceUntilIdle()
        assertEquals(listOf(Row(0)), vm.uiState.value.items)

        repeat(3) {
            vm.loadMore()
            advanceUntilIdle()
        }
        var state = vm.uiState.value
        assertEquals((0..3).map(::Row), state.items)
        assertTrue("第三页翻完预算用尽，应暂停", state.appendPaused)
        assertFalse("还有下一页，不是到底", state.reachedEnd)
        assertEquals(LoadState.Idle, state.append)
        assertEquals(4, source.loadCount)

        // 暂停中滚动触发一律忽略：不发请求、不动列表
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(4, source.loadCount)
        assertEquals((0..3).map(::Row), vm.uiState.value.items)

        // footer 那一下点击：再给一份预算并立刻翻一页
        vm.continueAppend()
        advanceUntilIdle()
        state = vm.uiState.value
        assertEquals((0..4).map(::Row), state.items)
        assertFalse(state.appendPaused)
        assertEquals(5, source.loadCount)

        // 新预算同样是 3 页：第 5、6 页翻完再次暂停
        repeat(2) {
            vm.loadMore()
            advanceUntilIdle()
        }
        state = vm.uiState.value
        assertEquals((0..6).map(::Row), state.items)
        assertTrue(state.appendPaused)
        assertEquals(7, source.loadCount)
    }

    @Test
    fun `continueAppend is a no-op unless paused`() = runTest(dispatcher) {
        val source = PolicySource(onePerPage(4), noThrottle)
        val vm = FeedViewModel(source)
        advanceUntilIdle()
        vm.continueAppend()
        advanceUntilIdle()
        assertEquals(1, source.loadCount)
        assertEquals(listOf(Row(0)), vm.uiState.value.items)
    }

    @Test
    fun `pages spent chasing empty pages come out of the same budget`() = runTest(dispatcher) {
        // 首页之后两页整页滤空（线上「薄页 / 空页」形态），再跟真正的新页
        val source = PolicySource(
            listOf(listOf(Row(1)), emptyList(), emptyList(), listOf(Row(2)), listOf(Row(3))),
            FeedPagingPolicy(maxAutoPages = 2, minPageIntervalMs = 0),
        )
        val vm = FeedViewModel(source)
        advanceUntilIdle()

        vm.loadMore()
        advanceUntilIdle()
        val state = vm.uiState.value
        // 两跳空页正好烧完 2 页预算：暂停，而不是当成到底（后面明明还有内容）
        assertEquals(listOf(Row(1)), state.items)
        assertTrue(state.appendPaused)
        assertFalse(state.reachedEnd)
        assertEquals(3, source.loadCount)

        vm.continueAppend()
        advanceUntilIdle()
        assertEquals(listOf(Row(1), Row(2)), vm.uiState.value.items)
        assertFalse(vm.uiState.value.appendPaused)
    }

    @Test
    fun `refresh starts a fresh budget and clears the pause`() = runTest(dispatcher) {
        val source = PolicySource(onePerPage(10), FeedPagingPolicy(maxAutoPages = 2, minPageIntervalMs = 0))
        val vm = FeedViewModel(source)
        advanceUntilIdle()
        repeat(2) {
            vm.loadMore()
            advanceUntilIdle()
        }
        assertTrue(vm.uiState.value.appendPaused)

        vm.refresh()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.appendPaused)
        assertEquals(listOf(Row(0)), vm.uiState.value.items)

        repeat(2) {
            vm.loadMore()
            advanceUntilIdle()
        }
        assertEquals((0..2).map(::Row), vm.uiState.value.items)
        assertTrue("新一代的预算同样是 2 页", vm.uiState.value.appendPaused)
    }

    @Test
    fun `consecutive page loads are spaced by the policy interval`() = runTest(dispatcher) {
        val source = PolicySource(onePerPage(8), FeedPagingPolicy(maxAutoPages = 30, minPageIntervalMs = 1_000))
        val vm = FeedViewModel(source, clock = { testScheduler.currentTime })
        advanceUntilIdle() // 首屏于 t=0
        assertEquals(1, source.loadCount)

        // 刷新后的第一次翻页不等：首屏填不满屏时（#1059 的补触发）第二页要立刻来
        vm.loadMore()
        dispatcher.scheduler.runCurrent()
        assertEquals(2, source.loadCount)
        assertEquals(0L, testScheduler.currentTime)

        // 紧接着的第三页要等到 t=1000 才发出，等待期间 footer 已是 Loading
        vm.loadMore()
        dispatcher.scheduler.runCurrent()
        assertEquals(LoadState.Loading, vm.uiState.value.append)
        assertEquals(2, source.loadCount)
        advanceTimeBy(999)
        dispatcher.scheduler.runCurrent()
        assertEquals(2, source.loadCount)
        advanceTimeBy(1)
        dispatcher.scheduler.runCurrent()
        assertEquals(3, source.loadCount)
        assertEquals((0..2).map(::Row), vm.uiState.value.items)
        assertEquals(LoadState.Idle, vm.uiState.value.append)

        // 下一页同样以上一页返回时刻（t=1000）起算
        vm.loadMore()
        advanceTimeBy(500)
        dispatcher.scheduler.runCurrent()
        assertEquals(3, source.loadCount)
        advanceTimeBy(500)
        dispatcher.scheduler.runCurrent()
        assertEquals(4, source.loadCount)

        // 隔了很久再翻：间隔早已满足，立刻发出，不白等
        advanceTimeBy(10_000)
        vm.loadMore()
        dispatcher.scheduler.runCurrent()
        assertEquals(5, source.loadCount)
    }

    @Test
    fun `a refresh chase through empty first pages is never paced`() = runTest(dispatcher) {
        // #729：首页整页滤空，追载两跳才有内容——首屏不能因为节流多等两秒
        val source = PolicySource(
            listOf(emptyList(), emptyList(), listOf(Row(1))),
            FeedPagingPolicy(maxAutoPages = 30, minPageIntervalMs = 1_000),
        )
        val vm = FeedViewModel(source, clock = { testScheduler.currentTime })
        dispatcher.scheduler.runCurrent()
        assertEquals(3, source.loadCount)
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(listOf(Row(1)), vm.uiState.value.items)
    }

    @Test
    fun `a pause between pages resets the budget so a person reading deep is never stopped`() = runTest(dispatcher) {
        val source = PolicySource(
            onePerPage(20),
            FeedPagingPolicy(maxAutoPages = 2, minPageIntervalMs = 0, burstIdleResetMs = 5_000),
        )
        val vm = FeedViewModel(source, clock = { testScheduler.currentTime })
        advanceUntilIdle()

        // 人的节奏：每页看 5 秒以上再翻——翻多少页都不会被拦
        repeat(6) {
            advanceTimeBy(5_000)
            vm.loadMore()
            dispatcher.scheduler.runCurrent()
            assertFalse("第 ${it + 1} 页后不该暂停", vm.uiState.value.appendPaused)
        }
        assertEquals(7, source.loadCount)

        // 机器的节奏：看够 5 秒翻一页（预算归零后计 1），紧接着零间隔再翻一页就到预算
        advanceTimeBy(5_000)
        vm.loadMore()
        dispatcher.scheduler.runCurrent()
        assertFalse(vm.uiState.value.appendPaused)
        vm.loadMore()
        dispatcher.scheduler.runCurrent()
        assertTrue(vm.uiState.value.appendPaused)
        assertEquals(9, source.loadCount)
    }

    @Test
    fun `adopting a cursor clears the pause so reachedEnd and paused never coexist`() = runTest(dispatcher) {
        val source = PolicySource(onePerPage(6), FeedPagingPolicy(maxAutoPages = 1, minPageIntervalMs = 0))
        val vm = FeedViewModel(source)
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.appendPaused)

        vm.adoptCursor(null)
        val state = vm.uiState.value
        assertTrue(state.reachedEnd)
        assertFalse(state.appendPaused)
    }

    @Test
    fun `manual continue after a pause is paced like any other page`() = runTest(dispatcher) {
        val source = PolicySource(onePerPage(8), FeedPagingPolicy(maxAutoPages = 1, minPageIntervalMs = 1_000))
        val vm = FeedViewModel(source, clock = { testScheduler.currentTime })
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle() // 第二页于 t=0（刷新后首次翻页不等），预算用尽
        assertTrue(vm.uiState.value.appendPaused)
        assertEquals(0L, testScheduler.currentTime)

        vm.continueAppend()
        dispatcher.scheduler.runCurrent()
        assertFalse(vm.uiState.value.appendPaused)
        assertEquals(2, source.loadCount)
        advanceTimeBy(1_000)
        dispatcher.scheduler.runCurrent()
        assertEquals(3, source.loadCount)
    }

    @Test
    fun `the first page of a refresh is never delayed and cancels a paced append`() = runTest(dispatcher) {
        val source = PolicySource(onePerPage(8), FeedPagingPolicy(maxAutoPages = 30, minPageIntervalMs = 1_000))
        val vm = FeedViewModel(source, clock = { testScheduler.currentTime })
        advanceUntilIdle()
        vm.loadMore()
        dispatcher.scheduler.runCurrent() // 第二页，t=0
        assertEquals(2, source.loadCount)

        vm.loadMore() // 第三页卡在间隔等待上
        advanceTimeBy(500)
        dispatcher.scheduler.runCurrent()
        assertEquals(LoadState.Loading, vm.uiState.value.append)

        vm.refresh() // 下拉刷新永远赢，且首屏不节流
        dispatcher.scheduler.runCurrent()
        assertEquals(3, source.loadCount)
        val state = vm.uiState.value
        assertEquals(LoadState.Idle, state.refresh)
        assertEquals(LoadState.Idle, state.append)
        assertEquals(listOf(Row(0)), state.items)
        // 被取消的那次追加不会在它原定的时刻偷偷发出去
        advanceTimeBy(5_000)
        dispatcher.scheduler.runCurrent()
        assertEquals(3, source.loadCount)
    }

    @Test
    fun `zero interval policy does not delay at all`() = runTest(dispatcher) {
        val source = PolicySource(onePerPage(4), noThrottle)
        val vm = FeedViewModel(source, clock = { testScheduler.currentTime })
        advanceUntilIdle()
        vm.loadMore()
        dispatcher.scheduler.runCurrent()
        assertEquals(2, source.loadCount)
        assertEquals(0L, testScheduler.currentTime)
    }
}
