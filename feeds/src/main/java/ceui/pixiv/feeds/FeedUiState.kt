package ceui.pixiv.feeds

/**
 * 整个列表页面的单一不可变状态（unidirectional data flow）。
 *
 * ViewModel 只发射这个对象，Fragment 是纯渲染函数：任何 UI 结论
 * （全屏 loading / 全屏错误 / 空态 / 底部追加条）都是它的派生值，
 * 不允许 View 层自己攒状态。
 */
data class FeedUiState(
    /** 当前已加载的全部条目（不含框架自动追加的 footer）。 */
    val items: List<FeedItem> = emptyList(),
    /** 首屏加载 / 下拉刷新的状态。 */
    val refresh: LoadState = LoadState.Idle,
    /** 向后翻页的状态。 */
    val append: LoadState = LoadState.Idle,
    /** 是否已翻到底（没有下一页游标）。 */
    val reachedEnd: Boolean = false,
    /**
     * 自动翻页预算（[FeedPagingPolicy.maxAutoPages]）已用完：还有下一页，但不再由滚动触发，
     * footer 变成「点击加载更多」，等用户点 [FeedViewModel.continueAppend] 再给一份预算。
     * 与 [reachedEnd] 互斥（到底了就没有「继续」可言）：refresh 整代提交和游标交接
     * （[FeedViewModel.adoptCursor]）都把它清掉。
     */
    val appendPaused: Boolean = false,
    /** 是否成功完成过至少一次刷新，用于区分「首屏加载」和「后续刷新」。 */
    val hasLoadedOnce: Boolean = false,
    /**
     * 当前这一代 [items] 是 [FeedSource.loadFromCache] 恢复的磁盘快照（本地优先），而不是刚下行的
     * 网络数据。**只在整代提交时翻**：缓存首屏置 true，网络首屏置 false；刷新失败不动它——
     * 屏幕上那一代**仍然是**快照，只是不再「更新中」了。
     *
     * 这是个关于**数据来源**的事实，不是 UI 结论（「是否显示更新中」是它的派生值，见 [showingCache]）。
     * 需要它的不只是 UI：快照最长 [ceui.pixiv.feeds.cache.DEFAULT_FEED_CACHE_MAX_AGE]，
     * 拿旧数据重放「拉取成功」的副作用会污染下游。[FeedLoadPhase.CacheRestore] 只覆盖到
     * [FeedSource] 边界内的 mapper；住在 Fragment 层、靠 collect 本状态驱动的副作用消费方
     * （`:app` 的 `IllustFeedPoolSync` 喂 ObjectPool / 关注态）拿不到 phase，只能读这个字段。
     * 新增此类消费方时务必门控它，否则陈旧 bean 会把更新的收藏 / 关注态盖回去。
     *
     * ⚠️ 数据源关掉「命中快照后自动刷新」（[FeedSource.refreshAfterCacheHit] 返回 false，如首页推荐
     * 的启动自动刷新开关）时，快照这一代是**终态**：不会有网络那一代来把它翻成 false，而且
     * [FeedViewModel.loadMore] 从此可以在它为 true 期间追加真·网络页。所以本字段的含义要按
     * 「这一代**起源**是磁盘」读，不能当成「列表里每一条都是陈旧的」。副作用消费方保持整代跳过
     * 即可——跳过是安全那一侧（顶多少一次预热，下游各自会补拉）；千万别为了照顾追加的尾部
     * 就在追加时把它翻成 false：随后任意一次 [FeedViewModel.mutateItems]（收藏广播绕回等）
     * 会推进 structureVersion 触发全量重扫，把陈旧的缓存前缀一起喂下去，正是门控要挡的事。
     */
    val itemsFromCache: Boolean = false,
    /**
     * 结构版本号：items 发生「非纯追加」变化（refresh 整代替换、[FeedViewModel.mutateItems]
     * 的默认结构性编辑）时自增；[FeedViewModel.loadMore] / [FeedViewModel.appendItems] 的纯尾部
     * 追加不推进它——那两条路径保证旧前缀元素引用不变（`existing + fresh`）。
     *
     * 增量消费方（如 IllustFeedPoolSync）借此判断：版本没变 + 列表变长，说明只是追加，
     * 只需扫描新增的尾部；版本变了就必须假设任意位置的条目实例都可能换了，全量重扫。
     */
    val structureVersion: Int = 0,
    /**
     * 整代替换的代号：**只有** [FeedViewModel.refresh] 的整代提交（磁盘缓存首屏、网络首屏）
     * 才自增；loadMore / appendItems / updateItems / removeItems 一律不推进。
     *
     * 存在的理由是 [structureVersion] 表达不了「整代换人」这件事：点赞、删除一条也会推进
     * structureVersion，拿它当整代信号会让每次点赞都把列表清表回顶。
     *
     * [FeedFragment] 用它决定是否**绕开跨代 diff**：新旧两代之间往往有个别 id 恰好重合
     *（榜单名次变了、推荐流回吐同一作品），ListAdapter 默认 `detectMoves=true` 会把这几个
     * 重合项当 move 锚点做移动动画，而 StaggeredGridLayoutManager 对 move + 整行重排有已知
     * 缺陷（本仓 `:app` 的 `StaggeredManager` 就在吞它抛的 IndexOutOfBounds）——
     * 结果就是用户看到的「旧数据往上顶一下、塌成零散卡片和黑色空档，再重排成新数据」。
     * 顺带一个观感问题：重合项连 holder 带已解码的图一起被复用，所以它秒显，而全新项要等
     * Glide 走网络，两者一先一后，整屏看着很割裂。
     *
     * 整代替换本就没有「移动」语义（是换了一批内容，不是同一批换了位置），不该让 DiffUtil
     * 去猜。
     */
    val refreshGeneration: Int = 0,
) {

    /**
     * 正在展示磁盘缓存的旧首屏、且网络刷新仍在进行（本地优先的「更新中」）。
     *
     * 派生自 [itemsFromCache] ∧ 刷新中，不是存储字段：刷新一旦停下（成功=已被新数据替换，
     * 此时 itemsFromCache 已翻 false；失败=内容仍在但不再是「更新中」），它自动为 false。
     * 默认无额外视觉（render 靠 hasLoadedOnce + refresh=Loading 已得到「内容 + 顶部刷新圈」），
     * 想显式加「更新中」胶囊的页面直接绑它即可，不会误亮。
     *
     * ⚠️ 副作用门控**不要**读这个（要读 [itemsFromCache]）：刷新失败时它为 false，而屏幕上那一代
     * 仍是快照——拿它当「这批数据新鲜吗」的判据，恰好会在离线时把陈旧 bean 放行。
     */
    val showingCache: Boolean
        get() = itemsFromCache && refresh is LoadState.Loading

    /** 首屏还没出过数据时的全屏加载态。 */
    val showFullscreenLoading: Boolean
        get() = refresh is LoadState.Loading && !hasLoadedOnce

    /** 页面上没有任何内容可以展示时的全屏错误态（点击整体重试）。 */
    val showFullscreenError: Boolean
        get() = refresh is LoadState.Error && items.isEmpty()

    /** 成功加载过、但确实没有内容的空态。 */
    val showEmptyState: Boolean
        get() = refresh is LoadState.Idle && hasLoadedOnce && items.isEmpty()
}
