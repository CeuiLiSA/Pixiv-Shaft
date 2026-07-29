package ceui.pixiv.ui.common

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import ceui.lisa.helper.AppLevelViewModelHelper
import ceui.lisa.model.ListIllust
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Params
import ceui.loxia.ObjectPool
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedUiState
import ceui.pixiv.feeds.FeedViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 插画列表与 legacy 详情链路的广播协同，从 [IllustFeedFragment] 拆出的独立协作件
 * （不依赖 Fragment 继承，混排页等任何 feeds 页面都能挂）：
 *
 * - FRAGMENT_ADD_DATA：详情 pager 用 nextUrl 续拉的页追加回列表并接管游标；
 * - FRAGMENT_SCROLL_TO_POSITION：返回时列表跟到详情页正在看的那张。
 *
 * 收藏态回流（LIKED_ILLUST）不在这里：它与小说 / 画师那两条广播是同一件事，统一走
 * [FeedLikeSync]（本类 [bind] 内一并挂上，调用方无感）。
 *
 * ADD_DATA 的 bean→条目映射是整页 gson 往返，不允许占主线程（广播恰恰在详情页
 * 滑动动画进行中到达）；这里经 [Channel] 单消费者搬到 Default 线程执行——
 * 单消费者保证按广播到达顺序追加 + 交接游标，晚到的旧页不会覆盖新游标。
 *
 * 生命周期随 viewLifecycleOwner：DESTROYED 时注销接收器并关闭队列，无需手动清理。
 */
class IllustFeedDetailSync(
    private val feedViewModel: FeedViewModel<String>,
    private val listPageUuid: String,
    private val itemFromBean: (IllustsBean?) -> IllustFeedItem?,
    /** 详情页正看到某张作品：illustId 按 id 锚定（缺省 0），pagerIndex 是快照下标兜底。 */
    private val onDetailScrolledTo: (illustId: Long, pagerIndex: Int) -> Unit,
) {

    fun bind(context: Context, viewLifecycleOwner: LifecycleOwner) {
        val broadcastManager = LocalBroadcastManager.getInstance(context)
        val addDataQueue = Channel<ListIllust>(Channel.UNLIMITED)

        // 收藏态回流与小说 / 画师两条广播同形，收口在 FeedLikeSync（自带注销）
        feedLikeSync<IllustFeedItem>(
            feedViewModel = feedViewModel,
            action = Params.LIKED_ILLUST,
            idOf = { it.illust.id },
            transform = { item, liked -> item.withBookmarked(liked) },
        ).bind(context, viewLifecycleOwner)

        val addDataReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val extras = intent?.extras ?: return
                if (extras.getString(Params.PAGE_UUID) != listPageUuid) return
                val listIllust = extras.getSerializable(Params.CONTENT) as? ListIllust ?: return
                addDataQueue.trySend(listIllust)
            }
        }
        val scrollReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val extras = intent?.extras ?: return
                if (extras.getString(Params.PAGE_UUID) != listPageUuid) return
                onDetailScrolledTo(
                    extras.getInt(Params.ID).toLong(),
                    extras.getInt(Params.INDEX, -1),
                )
            }
        }

        broadcastManager.registerReceiver(addDataReceiver, IntentFilter(Params.FRAGMENT_ADD_DATA))
        broadcastManager.registerReceiver(
            scrollReceiver, IntentFilter(Params.FRAGMENT_SCROLL_TO_POSITION)
        )

        // 用 lifecycleScope 而不是 repeatOnLifecycle：广播到达时本页通常在详情页背后
        // （STOPPED），追加要照做，返回列表时数据已就位
        viewLifecycleOwner.lifecycleScope.launch {
            for (listIllust in addDataQueue) {
                // itemFromBean 会跑用户 mapper（子类可覆写）→ passesContentFilters，那里面是三次
                // 同步 Room 查询（IllustNovelFilter 的 judgeTag/judgeID/judgeUserID）。它抛出来的话
                // 后果有两层，都很糟：lifecycleScope 没有 CoroutineExceptionHandler，异常直奔线程
                // 默认处理器 → 崩进程（Shaft 那个重入 Looper.loop 的兜底接不住协程异常）；而且这个
                // for 循环会**永久终止**，此后本 view 生命周期内所有详情续拉的页全部静默丢弃、
                // adoptCursor 再不执行，用户看不到任何错误。
                // 框架对同一个 mapper 在别处都显式兜了（FeedViewModel.refresh/loadMore/loadFromCache
                // 全包了 try/catch，注释写着「绝不能崩进程」），这里是唯一漏的一处。取消照常传播。
                val fresh = try {
                    withContext(Dispatchers.Default) {
                        listIllust.list.orEmpty().mapNotNull(itemFromBean)
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (ex: Throwable) {
                    Timber.w(ex, "feeds: 详情回传页映射失败，跳过这一页（列表照常可用）")
                    continue
                }
                feedViewModel.appendItems(fresh)
                feedViewModel.adoptCursor(listIllust.nextUrl?.takeIf { it.isNotEmpty() })
            }
        }

        viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                broadcastManager.unregisterReceiver(addDataReceiver)
                broadcastManager.unregisterReceiver(scrollReceiver)
                addDataQueue.close()
            }
        })
    }
}

/**
 * 列表数据落地后把最新 bean 合入 ObjectPool（主线程；对齐 legacy Mapper 的池同步职责，
 * 否则 V3 详情命中旧池条目会渲染过期的收藏数/爱心），并把作者关注状态灌进
 * AppLevelViewModel（对齐 legacy NetListFragment 每页 tidyAppViewModel，
 * UActivity/UserActivityV3 的关注按钮消费它）。
 *
 * **只喂真正下行的网络数据**：本地优先恢复的那一代（[FeedUiState.itemsFromCache]）整代跳过 ——
 * 合池是「拉取成功」的副作用，拿磁盘快照重放会把更新的收藏 / 关注态盖回去（详见 collect 内注释）。
 * 这条与 [ceui.pixiv.feeds.FeedLoadPhase.CacheRestore] 是同一件事的两半：phase 管住 [FeedSource]
 * 边界内的 mapper 副作用，本状态字段管住 Fragment 层这些靠 collect 驱动的消费方。
 *
 * 按 bean 实例去重（[IllustFeedSyncViewModel.pooledBeans]）：同一实例只合一次，
 * 刷新产出的同 id 新实例携带更新的服务端数据，必须重新合入——按 id 永久去重会把它挡在池外。
 *
 * 扫描范围借 [FeedUiState.structureVersion] 做增量：无限滚动场景下 loadMore 每次都是
 * `existing + fresh`（旧前缀条目引用不变，早就合过池），版本不变时只需扫描新追加的尾部；
 * 否则（refresh 整代替换、mutateItems 结构性编辑）没法假设任何位置的实例没变，退回全量重扫。
 * 不这样做的话，每次 loadMore 都会把从头到尾的旧条目重新扫一遍，翻页越深单次扫描越贵。
 */
class IllustFeedPoolSync(
    private val syncViewModel: IllustFeedSyncViewModel,
    private val poolableBeansOf: (FeedItem) -> List<IllustsBean>,
) {

    fun bind(viewLifecycleOwner: LifecycleOwner, uiState: StateFlow<FeedUiState>) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var scannedItems: List<FeedItem>? = null
                var scannedSize = 0
                var scannedVersion = -1
                uiState.collect { state ->
                    // 只有条目列表本身换了才扫描；纯加载态变化（append Loading/Idle）直接跳过
                    if (state.items === scannedItems) return@collect
                    // 本地优先恢复的那一代**绝不喂池**：磁盘快照最长
                    // [ceui.pixiv.feeds.cache.DEFAULT_FEED_CACHE_MAX_AGE]，里面的 is_bookmarked=false /
                    // is_followed=false / total_bookmarks 全是「正经值」而非空值，而 ObjectPool 的
                    // mergeKeepingExisting 只把 null / 空串 / 空数组当空 —— 旧值会原样盖掉池里更新的
                    // 收藏态、收藏数；AppLevelViewModel 的默认 method 也只在传入 FOLLOWED 时早退，
                    // 旧的 NOT_FOLLOW 会把刚点的「已关注」打回。#897 的「只补字段不降级」不变量挡不住
                    // 这类污染：快照存的是完整 bean，坏的是新鲜度不是完整度。
                    //
                    // 门控读 itemsFromCache 而**不是** showingCache：后者刷新失败时为 false，而屏幕上
                    // 那一代仍是快照，读它会恰好在离线（本地优先最该起作用的场景）放行陈旧 bean。
                    //
                    // 这里 return 时不推进扫描游标，所以网络那一代落地时（structureVersion 已自增）
                    // 照常全量重扫，缓存代跳过的条目会被新鲜实例补上。
                    //
                    // 例外：数据源关掉了「命中快照后自动刷新」（首页推荐的启动自动刷新开关）时没有
                    // 「网络那一代」，快照就是终态，本页这一轮 loadMore 追加的真·网络页也一并被跳过。
                    // 这是刻意选的安全侧：合池只是预热，漏了下游各自会补拉（V3 详情池未命中就回
                    // v1/illust/detail，关注态由用户页自己拉），而放行会让最长 7 天的旧 bean 盖掉
                    // 池里更新的收藏 / 关注态。别改成「追加时翻 false」，理由见 FeedUiState.itemsFromCache。
                    if (state.itemsFromCache) return@collect
                    val canScanTailOnly = state.structureVersion == scannedVersion &&
                            state.items.size >= scannedSize
                    val itemsToScan = if (canScanTailOnly) {
                        state.items.subList(scannedSize, state.items.size)
                    } else {
                        state.items
                    }
                    // poolableBeansOf 是子类可覆写的开放钩子，下游 ObjectPool.updateIllust /
                    // AppLevelViewModelHelper.fill 也都吃外部数据。这里抛出来的话后果与
                    // IllustFeedDetailSync 的 ADD_DATA 循环同类：collector 会连同
                    // repeatOnLifecycle 一起终止且**不自愈**（本 view 生命周期内此后所有列表数据
                    // 都不再合池，详情页从此渲染陈旧数据），异常还会经无 handler 的 lifecycleScope
                    // 直奔线程默认处理器崩掉进程。合池是旁路职责，失败绝不该拖垮浏览。
                    val freshBeans = mutableListOf<IllustsBean>()
                    try {
                        itemsToScan.forEach { item ->
                            poolableBeansOf(item).forEach { bean ->
                                if (syncViewModel.pooledBeans.put(bean.id.toLong(), bean) !== bean) {
                                    ObjectPool.updateIllust(bean)
                                    freshBeans.add(bean)
                                }
                            }
                        }
                        if (freshBeans.isNotEmpty()) {
                            AppLevelViewModelHelper.fill(freshBeans)
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (ex: Throwable) {
                        Timber.w(ex, "feeds: 合池失败，跳过本次扫描（下次数据变化重扫）")
                        return@collect
                    }
                    // 扫描游标在**成功之后**才推进：失败时保持旧游标，下一次发射会重扫这批条目。
                    // （放在扫描前推进的话，抛错那批就被永久判定为「已扫过」，再也进不了池。）
                    scannedItems = state.items
                    scannedSize = state.items.size
                    scannedVersion = state.structureVersion
                }
            }
        }
    }
}
