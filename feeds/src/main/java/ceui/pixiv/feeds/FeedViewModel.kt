package ceui.pixiv.feeds

import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.WeakHashMap

/**
 * 列表状态机。数据全部住在这里，Fragment 不持有任何数据（进程内配置变更零重载）。
 *
 * 并发规则（全部由本类保证，UI 层随便调不会坏）：
 * - refresh 永远赢：会取消进行中的任何加载；
 * - loadMore 单飞（single-flight）：Loading 中重入直接忽略；
 * - append 出错后不再被滚动自动触发，只能由 [retryAppend]（用户点击）恢复，避免错误风暴；
 * - 追加页按身份去重，服务端翻页窗口漂移不会产生重复条目破坏 DiffUtil。
 */
class FeedViewModel<Cursor : Any>(
    private val source: FeedSource<Cursor>,
    /** false = 懒加载：不在创建时拉首屏，由 [FeedFragment] 在首次 RESUMED 时补（见 [feedViewModels]）。 */
    internal val autoLoad: Boolean = true,
    /** [feedViewModels] 记录的游标类型，用于检测同 key 复用时的类型冲突；直接构造可不传。 */
    val cursorClass: Class<Cursor>? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()

    private var nextCursor: Cursor? = null
    private var refreshJob: Job? = null
    private var appendJob: Job? = null
    private var notifiedRefreshError: LoadState.Error? = null

    /** 当前翻页游标快照，用于把分页上下文交接给外部组件（如详情页 pager 续读）。 */
    val currentCursor: Cursor?
        get() = nextCursor

    init {
        if (autoLoad) {
            refresh()
        }
    }

    /**
     * 加载第一页。已有内容会保留在屏幕上直到新数据到达（不闪白屏）。
     *
     * 本地优先（数据源实现了 [FeedSource.loadFromCache] 时）：仅在**冷启**——本 VM 还没成功出过首屏
     * （`!hasLoadedOnce`，即进程重建 / 首次可见，非旋转 / 非下拉刷新）——先用磁盘快照秒显旧首屏，
     * 同时下面照常发网络刷新覆盖（哔哩哔哩 / 新闻首页语义）。旋转与下拉刷新时 hasLoadedOnce 已 true，
     * 不走缓存分支，不会刷新时闪回旧数据。
     *
     * 冷启时缓存尝试必须排在第一次 `_uiState.update` 之前：[FeedSource.loadFromCache] 是 suspend
     * （IO 派发 + gson 反序列化），哪怕只挂起几毫秒，如果先无条件把 refresh 置 Loading，
     * uiState 在缓存读完之前就已经是「Loading 且无内容」的快照，collector 一旦在这个窗口内开始
     * 观察（Fragment onViewCreated 到 Lifecycle 到 STARTED 之间通常就有这个间隙）就会画出一帧
     * 全屏转圈，冷启变成「转圈→缓存内容→网络内容」，而不是设计要的「秒显缓存内容」（小红书 /
     * 哔哩哔哩式：它们不会在读本地缓存期间对外暴露一个「正在加载」态）。所以这里先尝试读缓存、
     * 拿到确定结果后再发第一次 uiState 更新——对外只暴露一个起始终态：命中就直接从
     * 「内容 + 顶部刷新圈」起步，未命中 / 无缓存源才回退全屏 loading，中间没有可被画出来的帧。
     *
     * 首页同样有空页追载：mapper 把第一页整页滤空（重口味屏蔽设置 + #729 场景）时，
     * 继续向后翻，直到拿到可展示内容或确定到底——否则空列表上没有任何 onBind，
     * loadMore 的预取信号永远不会点火。
     */
    fun refresh() {
        refreshJob?.cancel()
        appendJob?.cancel()
        refreshJob = viewModelScope.launch {
            // 计时打点（本地优先耗时诊断）：从 refresh() 发起到缓存内容真正提交进 uiState
            // 的间隔，就是用户在冷启时会不会看到一帧多余转圈的直接证据。
            val refreshStartNanos = System.nanoTime()
            val coldStart = !_uiState.value.hasLoadedOnce
            // loadFromCache 会跑用户 mapper（缓存恢复态），可能在 gson 还原出的边界数据上抛错。
            // 必须自兜底：缓存恢复失败绝不能崩进程，吞掉照常走网络刷新（与网络首屏路径对称——
            // 那条 mapper 抛错也只是转 Error，不炸）。取消照常向上传播。
            val cached = if (coldStart) {
                try {
                    // 本地优先是 FeedSource 的可选默认能力：无缓存的源默认返回 null，直接调不用 as? 探测
                    source.loadFromCache()
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Throwable) {
                    Timber.w(ex, "feeds: 本地缓存恢复失败，回退网络刷新")
                    null
                }
            } else {
                null
            }

            if (cached != null && cached.items.isNotEmpty()) {
                // 数据源说「命中快照就别再自动刷了」（如首页推荐关掉了启动自动刷新，issue #955）：
                // 这一代快照就是终态，refresh 直接收成 Idle，下面的网络段整段跳过。
                val stayOnCache = !source.refreshAfterCacheHit()
                nextCursor = cached.nextCursor
                _uiState.update {
                    it.copy(
                        items = cached.items,
                        // 常规路径下 refresh 保持 Loading 表示网络刷新仍在下面进行；render 现成
                        // 逻辑 isRefreshing = refresh is Loading && hasLoadedOnce 让全屏 loading
                        // 让位给「内容 + 顶部刷新圈」。缓存旧游标翻页的竞态由 loadMore 现有的
                        // `refresh is Loading → return` 守卫免费挡住。
                        // stayOnCache 时没有后续网络段，直接收成 Idle：既不留假的转圈，也让
                        // 快照的游标立刻可以翻页。
                        refresh = if (stayOnCache) LoadState.Idle else LoadState.Loading,
                        append = LoadState.Idle,
                        reachedEnd = cached.nextCursor == null,
                        hasLoadedOnce = true,
                        // 停在快照上时也**照样**置 true：这一代确实来自磁盘，副作用消费方
                        //（IllustFeedPoolSync 喂 ObjectPool / 关注态）靠它门控，抹掉就会让陈旧
                        // bean 把更新的收藏 / 关注态盖回去。
                        itemsFromCache = true,
                        // 整代替换：磁盘快照的条目实例与之前任何一代都无关
                        structureVersion = it.structureVersion + 1,
                        refreshGeneration = it.refreshGeneration + 1,
                    )
                }
                val displayMs = (System.nanoTime() - refreshStartNanos) / 1_000_000
                Timber.d(
                    "feeds: 本地优先数据已展示，距 refresh() 发起 %dms（%d 条）",
                    displayMs, cached.items.size,
                )
                if (stayOnCache) {
                    Timber.d("feeds: 启动自动刷新已关闭，停在磁盘快照上，等用户下拉刷新")
                    return@launch
                }
            } else {
                _uiState.update { it.copy(refresh = LoadState.Loading, append = LoadState.Idle) }
                if (coldStart) {
                    val missMs = (System.nanoTime() - refreshStartNanos) / 1_000_000
                    Timber.d("feeds: 本地优先未命中（判定耗时 %dms），走常规全屏 loading", missMs)
                }
            }

            try {
                var page = source.load(null)
                // 新一代游标先记在局部，与 items 一起在成功时提交：中途失败（空页追载的
                // 后续请求挂掉）不能让屏幕上的旧一代列表配上新一代游标，否则后续
                // loadMore 会把两代内容混排进同一张列表
                var freshCursor = page.nextCursor
                var items = page.items.dedupByIdentity()
                var emptyHops = 0
                while (items.isEmpty() && freshCursor != null && emptyHops < MAX_EMPTY_PAGE_HOPS) {
                    page = source.load(freshCursor)
                    freshCursor = page.nextCursor
                    items = page.items.dedupByIdentity()
                    emptyHops++
                }
                val hopCapExhausted = items.isEmpty() && emptyHops >= MAX_EMPTY_PAGE_HOPS
                nextCursor = freshCursor
                _uiState.update {
                    it.copy(
                        items = items,
                        refresh = LoadState.Idle,
                        reachedEnd = freshCursor == null || hopCapExhausted,
                        hasLoadedOnce = true,
                        itemsFromCache = false,
                        // 整代替换：新一代网络首屏与屏幕上的旧条目实例无关
                        structureVersion = it.structureVersion + 1,
                        refreshGeneration = it.refreshGeneration + 1,
                    )
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Throwable) {
                Timber.e(ex)
                // 已用缓存兜底（items 非空）时：不覆盖内容，只置 refresh=Error，由 render 的
                // 「有内容刷新失败」一次性 Toast 提示；showFullscreenError 要求 items 为空，故缓存内容
                // 不被顶成全屏错误——离线冷启也能继续浏览上次的首屏。
                //
                // [FeedUiState.itemsFromCache] 刻意**不动**：屏幕上这一代仍然是磁盘快照，只是不再
                // 「更新中」了。「显示缓存且刷新仍在进行」由派生的 [FeedUiState.showingCache] 表达，
                // refresh 停在 Error 它自然为 false，无需在此手动归零——手动归零会把「这一代来自缓存」
                // 这个事实抹掉，而副作用消费方（IllustFeedPoolSync 喂 ObjectPool / 关注态）正是靠它
                // 门控的，抹掉就会在离线时把陈旧 bean 当新鲜数据放行、把更新的收藏 / 关注态盖回去。
                _uiState.update { it.copy(refresh = LoadState.Error(ex)) }
            }
        }
    }

    /**
     * 加载下一页。滚动接近底部时可高频调用，内部自带防重入。
     *
     * 预取由 onBind 驱动：如果某一页去重后零新条目（空页 / 整页被过滤或与已有内容重叠），
     * 列表不变、DiffUtil 零派发、onBind 不再发生，预取信号就断了。所以这里主动追载：
     * 只要拿到的页没有贡献新条目且还有游标，就继续取下一页（上限 [MAX_EMPTY_PAGE_HOPS]，
     * 触顶视为到底），保证一次 loadMore 结束后要么有新内容、要么确定到底。
     */
    fun loadMore() {
        val current = _uiState.value
        if (!current.hasLoadedOnce || current.reachedEnd) return
        if (current.refresh is LoadState.Loading) return
        // Loading 防重入；Error 停手等用户点重试
        if (current.append !is LoadState.Idle) return
        val firstCursor = nextCursor ?: return
        // 单飞的硬保证：上一个 appendJob 还活着（含已 launch 但尚未跑到 append=Loading 提交）就
        // 直接返回。上面的状态快照检查挡不住这个窗口——它依赖「viewModelScope 是 Main.immediate、
        // launch 同步跑到第一个挂起点」这个调度器行为，换成非 immediate 调度器（单测的
        // StandardTestDispatcher 就是）同步重入会放进第二个 job 把第一个悬空。查 job 存活与
        // 调度器无关，两层守卫叠加后重入在任何调度器下都安全。
        if (appendJob?.isActive == true) return
        appendJob = viewModelScope.launch {
            _uiState.update { it.copy(append = LoadState.Loading) }
            try {
                var cursor = firstCursor
                var emptyHops = 0
                while (true) {
                    val page = source.load(cursor)
                    nextCursor = page.nextCursor
                    // VM 的全部状态变更都在主线程串行发生，先读快照再算增量是安全的
                    val seen = _uiState.value.items.mapTo(HashSet()) { it.identity }
                    val fresh = page.items.filter { seen.add(it.identity) }
                    _uiState.update { state ->
                        state.copy(
                            // 零新条目时保持列表实例不变：等价新列表会白白触发一轮全量 diff
                            // 和下游按 identity 跳过的扫描（如 ObjectPool 合池）
                            items = if (fresh.isEmpty()) state.items else state.items + fresh,
                            reachedEnd = page.nextCursor == null,
                        )
                    }
                    val next = page.nextCursor
                    if (fresh.isNotEmpty() || next == null) break
                    if (++emptyHops >= MAX_EMPTY_PAGE_HOPS) {
                        Timber.w("feeds: 连续 %d 页零新条目，视为到底", emptyHops)
                        _uiState.update { it.copy(reachedEnd = true) }
                        break
                    }
                    cursor = next
                }
                _uiState.update { it.copy(append = LoadState.Idle) }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Throwable) {
                Timber.e(ex)
                _uiState.update { it.copy(append = LoadState.Error(ex)) }
            }
        }
    }

    /**
     * 外部组件（如详情页 pager）替这张列表继续翻过页之后，把最新游标交还回来，
     * 让列表接着它的进度翻，而不是拿旧游标重拉一遍已看过的页。
     *
     * 会取消在飞的追加：那次追加基于旧游标，让它跑完会把 nextCursor 回写成
     * 旧页的游标，悄悄覆盖掉刚交接来的进度。refresh 不受影响（refresh 永远赢）。
     */
    fun adoptCursor(cursor: Cursor?) {
        appendJob?.cancel()
        nextCursor = cursor
        _uiState.update { it.copy(append = LoadState.Idle, reachedEnd = cursor == null) }
    }

    /**
     * 原子地交接游标并编辑列表。详情页的“某区块首次可见 → 填区块状态 + 追加首批卡片 +
     * 开启后续分页”必须作为一个状态提交，否则连续的 appendItems / adoptCursor /
     * updateItems 会让 Fragment 连收三份 state、安排两轮无意义 diff。
     */
    fun adoptCursorAndMutateItems(
        cursor: Cursor?,
        edit: (List<FeedItem>) -> List<FeedItem>,
    ) {
        appendJob?.cancel()
        nextCursor = cursor
        _uiState.update { state ->
            val next = edit(state.items)
            state.copy(
                items = next,
                append = LoadState.Idle,
                reachedEnd = cursor == null,
                structureVersion = if (next !== state.items) {
                    state.structureVersion + 1
                } else {
                    state.structureVersion
                },
            )
        }
    }

    /**
     * 懒加载触发点：还没成功加载过首屏且当前没有在刷新时才发起加载。
     * `feedViewModels(autoLoad = false)` 的 VM 由 [FeedFragment.onResume] 自动调用，
     * 页面无需手写（约定闭环见 [feedViewModels] 文档）。
     * 首屏失败后再次可见会自动重试（对齐 legacy BaseLazyFragment 语义）。
     */
    fun ensureLoaded() {
        val current = _uiState.value
        if (current.hasLoadedOnce || current.refresh is LoadState.Loading) return
        refresh()
    }

    /**
     * 「屏幕上有内容兜底时刷新失败」的一次性提示判定：同一个 Error 实例只放行一次。
     * 已消费标记归 VM（跨视图重建 / 旋转存活），View 层不自己攒状态（UDF 约定）。
     */
    fun shouldNotifyRefreshError(error: LoadState.Error): Boolean {
        if (error === notifiedRefreshError) return false
        notifiedRefreshError = error
        return true
    }

    /** 追加失败后的手动重试入口（footer 点击）。 */
    fun retryAppend() {
        if (_uiState.value.append is LoadState.Error) {
            _uiState.update { it.copy(append = LoadState.Idle) }
            loadMore()
        }
    }

    /**
     * 整表编辑的通用入口：替换 / 插入 / 删除都可以由此表达。
     *
     * [edit] 必须是纯函数（不要在里面做 IO）。
     *
     * 唯一被承认的例外是「把同一次变更同步到该条目的其它共享表示」这一类**幂等**副作用
     * （典型：`:app` 的 `IllustFeedItem.withBookmarked` 就地写共享 bean + 同步
     * ObjectPool——那一刀必须落在变更点，理由见它自己的 KDoc）。它成立的依据是两条，缺一不可：
     * - VM 的状态变更全部发生在主线程，`MutableStateFlow.update` 因此不会有 CAS 争用、
     *   不会重放 lambda；
     * - 副作用幂等，即便重放也只是重复写同一个值。
     * 反过来说：**别在 [edit] 里改新旧两代条目共享的可变对象**（那等于把 DiffUtil 的 oldItem
     * 一起改掉，内容比较从此看不见变化），也别做任何非幂等或跨线程的事。需要那种编辑时，
     * 把副作用挪到调用点、[edit] 只负责产出新列表（`:app` 的 `LikeUsersFeedFragment`
     * 的 applyFollowed 就是这么写的）。
     *
     * [structural] 默认 true：编辑可能在任意位置替换 / 插入 / 删除条目，让
     * [FeedUiState.structureVersion] 自增，强制增量消费方（如 IllustFeedPoolSync）全量重扫。
     * 已知是纯尾部追加（旧前缀条目引用保证不变，如 `existing + fresh`）的调用方可传 false，
     * 让消费方只需扫描新增的尾部——[appendItems] 已经这样做，其余调用方保持默认。
     *
     * [edit] 原样返回入参列表（同一实例）即视为「什么也没改」：不换 items、不推进
     * structureVersion、不发新状态。零变化的编辑因此是真正免费的——否则一次 no-op 也会造出
     * 等价新列表，白白触发一轮全量 diff + 让下游（[FeedUiState.structureVersion] 的消费方）
     * 全表重扫。本页自己发起的收藏经广播绕回自己就是这种 no-op，很常见。
     */
    fun mutateItems(structural: Boolean = true, edit: (List<FeedItem>) -> List<FeedItem>) {
        _uiState.update { state ->
            val next = edit(state.items)
            if (next === state.items) {
                state
            } else {
                state.copy(
                    items = next,
                    structureVersion =
                        if (structural) state.structureVersion + 1 else state.structureVersion,
                )
            }
        }
    }

    /**
     * 把外部拿到的条目直接追加进列表（详情页 pager 替列表续拉的页等），
     * 纯内存操作不触发网络。身份去重由本方法保证（对全表 + 入参内部同时去重），
     * 调用方不需要自己维护「列表内身份唯一」这个 DiffUtil 前置不变量。
     * 通常与 [adoptCursor] 配套使用：先追加数据，再交接游标。
     */
    fun appendItems(newItems: List<FeedItem>) {
        if (newItems.isEmpty()) return
        mutateItems(structural = false) { existing ->
            val seen = existing.mapTo(HashSet()) { it.identity }
            val fresh = newItems.filter { seen.add(it.identity) }
            if (fresh.isEmpty()) existing else existing + fresh
        }
    }

    /**
     * 就地更新某一类条目（点赞 / 收藏切换等），不触发网络重载。
     *
     * [transform] 返回入参本身（同一实例）即「这条没变」；全表都没变时整次调用不产生任何状态变更
     *（见 [mutateItems]）。所以 transform 应当在已是目标态时原样返回——`withBookmarked` /
     * `withFollowed` 都是这么写的。
     */
    fun <T : FeedItem> updateItems(itemClass: Class<T>, transform: (T) -> T) {
        mutateItems { list ->
            // Copy-on-write：命中改动前不分配。最常见的调用形态是 like 广播绕回本页——transform
            // 幂等原样返回、零条目真的变（见 withBookmarked / withFollowed），此时该原样返回同一
            // 实例（[mutateItems] 据此判定「什么也没改」而不发新状态）。用 list.map 则每次都造一个
            // 等价新列表再丢掉，白白触发一轮全表 diff + structureVersion 消费方全表重扫。
            var mutated: ArrayList<FeedItem>? = null
            list.forEachIndexed { index, item ->
                if (itemClass.isInstance(item)) {
                    val updated = transform(itemClass.cast(item))
                    if (updated !== item) {
                        (mutated ?: ArrayList(list).also { mutated = it })[index] = updated
                    }
                }
            }
            mutated ?: list
        }
    }

    fun removeItems(predicate: (FeedItem) -> Boolean) {
        mutateItems { list ->
            val next = list.filterNot(predicate)
            if (next.size == list.size) list else next
        }
    }

    companion object {
        /** 连续零新条目页的追载上限，防止异常数据导致无限翻页。 */
        private const val MAX_EMPTY_PAGE_HOPS = 2
    }
}

/** [FeedViewModel.updateItems] 的 reified 便捷版：`viewModel.updateItems<IllustFeedItem> { ... }`。 */
inline fun <reified T : FeedItem> FeedViewModel<*>.updateItems(noinline transform: (T) -> T) {
    updateItems(T::class.java, transform)
}

/**
 * 在 Fragment 作用域内创建 / 复用 [FeedViewModel]，用法对齐 `by viewModels()`：
 *
 * ```
 * override val feedViewModel by feedViewModels {
 *     // 先把 Fragment 属性取成局部值再给 lambda 用（零捕获约定，见下）
 *     val userId = userId
 *     pixivFeedSource({ Client.appApi.getUserIllusts(userId) }) { resp, _ -> ... }
 * }
 * ```
 *
 * ⚠️ 零捕获约定：[sourceProvider] 造出的 FeedSource（含 initialFetch / mapper lambda）
 * 会被 VM 持有到页面最终销毁，而 VM 比 Fragment 实例活得久（进程内配置变更会重建
 * Fragment、复用 VM）——lambda 捕获 Fragment / View / Context 就把旋转前的旧实例
 * 钉在内存里了。需要 Fragment 属性（arguments 等）就在 sourceProvider 体内先读进
 * 局部 val；映射逻辑复杂就写成伴生对象 / 顶层函数（参考 RecmdIllustFeedFragment）。
 * mapper 里要用 Context 一律 Shaft.getContext()。
 *
 * 同一个 Fragment 需要多个列表 VM 时必须用 [key] 区分：同一 Fragment 实例内
 * 重复的 key 会立刻抛错（fail-fast），不会静默让两个列表共享同一个 VM。
 *
 * [autoLoad] 传 false 表示懒加载：数据等 tab 真正可见（首次 RESUMED）才拉，
 * [FeedFragment.onResume] 会自动触发 [FeedViewModel.ensureLoaded]，子类无需再写。
 * 用于 ViewPager 场景——相邻 tab 的 Fragment 会被提前创建，不该替用户偷偷请求
 * 从未打开过的 tab（R18 榜等尤其），宿主 pager 需用 BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT。
 */
inline fun <reified Cursor : Any> Fragment.feedViewModels(
    key: String? = null,
    autoLoad: Boolean = true,
    noinline sourceProvider: () -> FeedSource<Cursor>,
): Lazy<FeedViewModel<Cursor>> = feedViewModelsImpl(Cursor::class.java, key, autoLoad, sourceProvider)

/**
 * 每个 Fragment 实例已占用的列表 VM key（主线程访问）。weak key：
 * 记录随 Fragment 实例回收，重建的新实例从零开始重新认领。
 */
private val claimedFeedVmKeys = WeakHashMap<Fragment, MutableSet<String>>()

@PublishedApi
internal fun <Cursor : Any> Fragment.feedViewModelsImpl(
    cursorClass: Class<Cursor>,
    key: String?,
    autoLoad: Boolean,
    sourceProvider: () -> FeedSource<Cursor>,
): Lazy<FeedViewModel<Cursor>> = lazy(LazyThreadSafetyMode.NONE) {
    val storeKey = key ?: FeedViewModel::class.java.name
    // 同一个 Fragment 实例内第二个同 key 委托是接线错误：两个列表会静默共享一个 VM
    //（谁先初始化就用谁的数据源，另一个列表显示错误数据）。不论游标类型是否相同都立刻抛错。
    val claimed = claimedFeedVmKeys.getOrPut(this) { mutableSetOf() }
    check(claimed.add(storeKey)) {
        "feedViewModels key 撞车：key=${key ?: "<默认>"} 已被本 Fragment 的另一个列表占用，" +
                "同一 Fragment 的第二个列表 VM 请显式传 key"
    }
    val factory = viewModelFactory {
        initializer { FeedViewModel(sourceProvider(), autoLoad, cursorClass) }
    }
    val viewModel = ViewModelProvider(this, factory).get(storeKey, FeedViewModel::class.java)
    // 跨实例复用（旋转重建拿回旧 VM）时校验游标类型没有漂移
    check(viewModel.cursorClass == null || viewModel.cursorClass == cursorClass) {
        "feedViewModels key=${key ?: "<默认>"} 复用到 Cursor=${viewModel.cursorClass?.name} 的旧 VM，" +
                "与本次声明的 ${cursorClass.name} 不符"
    }
    @Suppress("UNCHECKED_CAST")
    viewModel as FeedViewModel<Cursor>
}
