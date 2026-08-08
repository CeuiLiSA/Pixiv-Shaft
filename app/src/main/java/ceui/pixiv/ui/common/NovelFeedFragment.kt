package ceui.pixiv.ui.common

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ImageButton
import androidx.annotation.LayoutRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.pixiv.ui.bookmark.SelectTagBottomSheet
import ceui.lisa.databinding.RecyNovelBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.lisa.utils.V3Palette
import ceui.lisa.view.LinearItemDecoration
import ceui.loxia.Client
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.loxia.getHumanReadableMessage
import ceui.pixiv.events.EventReporter
import ceui.pixiv.feeds.FeedCell
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedNovelSkeletonView
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSkeletonView
import ceui.pixiv.feeds.FeedViewModel
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.ui.novel.NovelSeriesFragment
import ceui.pixiv.ui.recommend.bindTrendingScore
import ceui.pixiv.utils.playLikePressHaptic
import ceui.pixiv.utils.pinHostGlide
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widgets.RateAppManager
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/** 收藏态局部重绑的 payload 标记（按引用识别）。 */
private val PAYLOAD_NOVEL_BOOKMARK = Any()

/**
 * 小说收藏提交用的进程级 scope（对齐插画侧：那边走 [ceui.lisa.utils.PixivOperate.postLike] 的全局
 * Rx 链，同样不随 view 死）。
 *
 * 收藏是**用户已经按下去的意图**，一旦乐观态写进了比 view 长命的 VM，请求就必须发完——挂
 * `viewLifecycleOwner.lifecycleScope` 的话（`ceui.loxia.launchSuspend` 就是），点完立刻返回上一页
 * 会让协程被取消：请求可能根本没到服务端，而 VM 里的乐观态还在（回退分支挂在 `catch (Exception)`
 * 之后，CancellationException 直接 rethrow 跳过它），回到列表显示已收藏、服务端却没有，且再无回滚时机。
 *
 * Main.immediate：回调（updateItems / toast / 广播）都要在主线程，且点击当帧同步跑到第一个挂起点。
 * SupervisorJob + handler：单次收藏失败不牵连其它，且游离 scope 里的漏网异常不许崩进程。
 */
private val novelBookmarkScope = CoroutineScope(
    SupervisorJob() +
        Dispatchers.Main.immediate +
        CoroutineExceptionHandler { _, t -> Timber.w(t, "小说收藏协程异常，忽略") }
)

/**
 * 小说列表页的共享基类（对齐插画侧 [IllustFeedFragment]）。子类只声明数据源
 *（feedViewModels + mapper 产出 [NovelFeedItem]）；本类统一提供主力小说卡（recy_novel）：
 *
 * - 全程 loxia [Novel] data class：收藏走 [Client] 的 addNovelBookmark/removeNovelBookmark，
 *   跳转走 [DetailFeedSupport] 的 openNovelDetail/openUserActivity，标签流 [ceui.pixiv.widgets.V3TagFlowView]
 *   直接吃 loxia [ceui.loxia.Tag]——不并存 legacy 可变 bean、不做 gson 往返；
 * - 收藏：乐观切态 + 尊重私密收藏设置 + 成功 toast + 收藏后自动关注作者(isAutoFollowAfterStar)
 *   + 事件埋点 + RateApp + 网络失败回退，并收发 LIKED_NOVEL 广播与其它小说列表双向同步收藏态；
 * - 点击语义：卡片开小说详情 / 封面看封面大图 / 头像·作者进画师页 / 系列进小说系列页 /
 *   爱心长按进「按标签收藏」；
 * - 收藏态只有 is_bookmarked / total_bookmarks 变时走局部重绑 payload，不重跑 Glide(对齐插画卡)；
 * - LinearLayoutManager 竖向列表（recy_novel 卡本身无 margin，靠 12dp LinearItemDecoration 分隔）。
 *
 * 卡片布局与全部交互语义源自 legacy `NAdapter`（迁移时逐条对齐）。**该类已随最后一个调用方
 * 一起删除**（见「NAdapter 三个页面全部迁 feeds」那次提交），要考古去 git 历史，别在工作区找。
 */
abstract class NovelFeedFragment(
    @LayoutRes contentLayoutId: Int = R.layout.fragment_feed,
) : FeedFragment(contentLayoutId) {

    abstract override val feedViewModel: FeedViewModel<String>

    /**
     * 封面 / 头像的 Glide 请求管理器，建一次复用（对齐插画侧 [IllustFeedFragment.illustGlide]）。
     * 别在每次 bind / recycle 里 `Glide.with(view)`：那条重载每次都递归遍历宿主 fragment 树找
     * 承载 view 的 fragment，一张卡 fling 时要跑好几次，全在帧路径上。`Glide.with(Fragment)`
     * 直接命中、无查找，解析出的又是同一个 RequestManager（view 本就在本 Fragment 里），行为等价。
     */
    private val novelGlide: RequestManager by lazy { Glide.with(this) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pinHostGlide(novelGlide)
        // 其它列表/详情页收藏某小说 → 广播回流本列表(双向同步;沿用 legacy CommonReceiver 的广播契约)
        feedLikeSync<NovelFeedItem>(
            feedViewModel = feedViewModel,
            action = Params.LIKED_NOVEL,
            idOf = { it.novel.id },
            transform = { item, liked -> item.withBookmarked(liked) },
        ).bind(requireContext(), viewLifecycleOwner)
    }

    override fun onListReady(listView: RecyclerView) {
        listView.addItemDecoration(LinearItemDecoration(12.ppppx))
    }

    /**
     * 竖向小说列表的骨架图长得像 recy_novel（左封面 + 右标题/系列/作者 + 标签流），不是瀑布流那种
     * 等宽块——基类默认只给 StaggeredGridLayoutManager 出骨架，小说列表是 Linear，得自己给。
     */
    override fun onCreateSkeletonView(layoutManager: RecyclerView.LayoutManager): FeedSkeletonView {
        return FeedNovelSkeletonView(requireContext())
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(novelCardRenderer())
    }

    /**
     * 主力小说卡（recy_novel）。收藏爱心的乐观翻色在点击处当帧完成（异步 updateItems 只是
     * 落地态兜底），封面/头像用 Glide 加载，recycle 清图避免复用错图。收藏态变更只局部重绑
     * 爱心 + 收藏数(changePayload/bindPayloads),不重跑封面 Glide。
     */
    protected fun novelCardRenderer() = feedRenderer<NovelFeedItem, RecyNovelBinding>(
        inflate = RecyNovelBinding::inflate,
        create = { cell ->
            cell.binding.root.setOnClick { openNovelDetail(cell.item.novel.id) }
            cell.binding.cover.setOnClick { openCoverImage(cell.item.novel) }
            cell.binding.userHead.setOnClick { openNovelAuthor(cell.item.novel) }
            cell.binding.author.setOnClick { openNovelAuthor(cell.item.novel) }
            cell.binding.like.setOnClick { toggleNovelLike(cell) }
            cell.binding.like.setOnLongClickListener {
                openNovelTagBookmark(cell.item.novel)
                true
            }
        },
        recycle = { cell ->
            novelGlide.clear(cell.binding.cover)
            novelGlide.clear(cell.binding.userHead)
        },
        changePayload = { old, new ->
            // 只有收藏态/收藏数变了 → 局部重绑;其它字段(含热度分)变则回退全量绑定
            if (old.trendingScore == new.trendingScore &&
                old.novel.copy(
                    is_bookmarked = new.novel.is_bookmarked,
                    total_bookmarks = new.novel.total_bookmarks,
                ) == new.novel
            ) PAYLOAD_NOVEL_BOOKMARK else null
        },
        bindPayloads = { cell, payloads ->
            if (payloads.all { it === PAYLOAD_NOVEL_BOOKMARK }) {
                val novel = cell.item.novel
                renderNovelLike(cell.binding.like, novel.is_bookmarked == true)
                cell.binding.bookmarkCount.text = (novel.total_bookmarks ?: 0).toString()
                true
            } else {
                false
            }
        },
    ) { cell -> bindNovelCard(cell) }

    private fun bindNovelCard(cell: FeedCell<NovelFeedItem, RecyNovelBinding>) {
        val b = cell.binding
        val novel = cell.item.novel
        val ctx = b.root.context
        val palette = V3Palette.from(ctx)

        // 系列：强调色文本 + 点击进小说系列页
        val series = novel.series
        if (series != null && !series.title.isNullOrEmpty()) {
            b.series.isVisible = true
            b.series.setTextColor(palette.textAccent)
            b.series.text = ctx.getString(R.string.string_184, series.title)
            b.series.setOnClick { openNovelSeries(series.id) }
        } else {
            b.series.isVisible = false
            b.series.setOnClickListener(null)
        }

        b.title.text = novel.title ?: ""
        b.author.text = novel.user?.name ?: ""
        b.date.text = novel.create_date?.take(10) ?: ""
        b.bookmarkCount.text = (novel.total_bookmarks ?: 0).toString()
        val wordCount = novel.text_length ?: 0
        b.howManyWord.text = ctx.getString(R.string.v3_novel_word_count, wordCount.toString())
        // 热度分（本月收藏/当前最热 shaft-api-v2 注入）露左上角 pill；普通列表 trendingScore=null → 自动隐藏。
        b.trendingScore.bindTrendingScore(cell.item.trendingScore)
        // AI 生成角标（novel_ai_type == 2，与 card/v3/history/detail 同口径）
        b.badgeAi.isVisible = novel.novel_ai_type == 2

        // 标签流：尊重「显示标签」设置，关时喂空列表折叠。compact + 去 # 前缀，
        // 「标签折叠」开关开启时超 6 个折叠成「+N」，关闭时 maxTags=-1 全量展示；
        // searchIndex=1 让点击跳搜索页「小说」tab。
        val tags = if (Shaft.sSettings.isShowNovelCardTags()) novel.tags.orEmpty() else emptyList()
        b.novelTag.compact = true
        b.novelTag.searchIndex = 1
        b.novelTag.showHashPrefix = false
        b.novelTag.maxTags = if (Shaft.sSettings.isCollapseNovelCardTags()) 6 else -1
        b.novelTag.setTags(tags)
        b.novelTag.isVisible = tags.isNotEmpty()

        val coverUrl = novel.image_urls?.let { it.large ?: it.medium ?: it.square_medium }
        novelGlide.load(GlideUtil.getUrl(coverUrl))
            .placeholder(R.color.v3_surface_2).error(R.color.v3_surface_2)
            .into(b.cover)
        novel.user?.let { novelGlide.load(GlideUtil.getHead(it)).into(b.userHead) }

        renderNovelLike(b.like, novel.is_bookmarked == true)
    }

    /** 未收藏=灰，已收藏=红（爱心图标 ic_like_illust_6 由布局给，这里只切 tint）。 */
    private fun renderNovelLike(button: ImageButton, liked: Boolean) {
        val color = if (liked) R.color.has_bookmarked else R.color.not_bookmarked
        button.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(button.context, color))
    }

    /** VM 里当前这条小说的最新条目（真源）；已被刷新挤掉则 null。对齐 [IllustFeedFragment.currentIllustItem]。 */
    private fun currentNovelItem(novelId: Long): NovelFeedItem? {
        return feedViewModel.uiState.value.items
            .firstOrNull { it is NovelFeedItem && it.novel.id == novelId } as? NovelFeedItem
    }

    /**
     * 收藏切换：点按当帧乐观翻心 + updateItems 落地(DiffUtil 局部重绑),再走 loxia 网络。
     * 与 legacy NAdapter.postLikeNovel 逐条对齐:尊重私密收藏 / 成功 toast / 收藏后自动关注作者 /
     * 事件埋点 / RateApp / 发 LIKED_NOVEL 广播同步其它列表。网络失败回退并弹 toast。
     */
    private fun toggleNovelLike(cell: FeedCell<NovelFeedItem, RecyNovelBinding>) {
        // 收藏态的真源是 VM 的当前状态，不是 cell.item —— 后者是 adapter **已提交的快照**，要等
        // ListAdapter 后台 diff 落地才经 cell.attach 换新（下面那行注释自己写了「至少一两帧」）。
        // 读 cell.item 的后果：连点两下时第二下仍看到上一下之前的旧态，把「取消收藏」反转成
        // 「再收藏一次」——心不回灰、两条 toast、两次 addNovelBookmark，取消这个操作直接丢失。
        // 插画侧 staggerIllustRenderer 早已这么修，此处对齐。
        val tapped = cell.itemOrNull ?: return
        val novelId = tapped.novel.id
        val novel = (currentNovelItem(novelId) ?: tapped).novel
        val target = novel.is_bookmarked != true
        // 乐观：当帧翻心（异步 updateItems 至少要等 ListAdapter diff 落地一两帧）
        renderNovelLike(cell.binding.like, target)
        // 收藏触感（与插画卡共用 playLikePressHaptic）：收藏给 iOS 3D-touch 段落感,取消给单下轻 tick
        if (target) {
            playLikePressHaptic(cell.binding.like)
        } else {
            cell.binding.like.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        applyNovelBookmark(novelId, target)
        val restrict = if (Shaft.sSettings.isPrivateStar()) Params.TYPE_PRIVATE else Params.TYPE_PUBLIC
        // VM 先取成局部值：提交跑在比 view 长命的 scope 上，此后一律不再碰 Fragment
        //（见 [novelBookmarkScope]；Fragment 已销毁时碰它的属性可能抛）。
        val viewModel = feedViewModel
        val appContext = Shaft.getContext()
        novelBookmarkScope.launch {
            // 只有收藏网络调用本身失败才回退 UI + 提示。收藏成功之后的埋点/toast/关注/广播
            // 都是「收藏已成功」的后续动作,任一失败都不能把已成功的收藏回退掉误导用户
            //（对齐 legacy postLikeNovel:自动关注是独立 fire-and-forget,失败静默）。
            try {
                if (target) {
                    Client.appApi.addNovelBookmark(novelId, restrict)
                } else {
                    Client.appApi.removeNovelBookmark(novelId)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Exception) {
                // 回退条目状态,可见卡片重绑爱心
                applyNovelBookmark(viewModel, novelId, !target)
                Timber.e(ex, "小说收藏失败")
                // 文案映射自身也可能出岔子（HttpException 的错误体只能读一次等），
                // 或映射出空白 —— 错误提示这条路上不允许再抛第二个异常、也不该弹空 toast。
                val message = runCatching { ex.getHumanReadableMessage(appContext) }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: appContext.getString(R.string.v3_widget_bookmark_failed)
                Common.showToast(message)
                return@launch
            }

            // ↓ 收藏已提交成功,以下副作用失败均不回退收藏
            if (target) RateAppManager.onUserEngaged()
            EventReporter.report(
                if (target) EventReporter.Type.BOOKMARK else EventReporter.Type.UNBOOKMARK,
                EventReporter.Target.NOVEL,
                novelId,
                novel,
            )
            Common.showToast(
                appContext.getString(
                    when {
                        !target -> R.string.cancel_like_illust
                        restrict == Params.TYPE_PUBLIC -> R.string.like_novel_success_public
                        else -> R.string.like_novel_success_private
                    }
                )
            )
            // 广播同步其它小说列表(含仍 legacy 的收藏/画师小说列表)。会回流到本列表自己的
            // receiver,withBookmarked 幂等,无副作用。
            sendNovelLikedBroadcast(novelId, target)
            // 收藏后自动关注作者(对齐 legacy postLikeNovel):独立 try,失败静默,绝不回退收藏
            val user = novel.user
            if (target && Shaft.sSettings.isAutoFollowAfterStar() &&
                user != null && user.is_followed != true
            ) {
                try {
                    Client.appApi.postFollow(user.id, Params.TYPE_PUBLIC)
                    ObjectPool.followUser(user.id)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (ex: Exception) {
                    Timber.w(ex, "小说收藏后自动关注作者失败(静默)")
                }
            }
        }
    }

    private fun applyNovelBookmark(novelId: Long, liked: Boolean) {
        applyNovelBookmark(feedViewModel, novelId, liked)
    }

    private fun applyNovelBookmark(
        viewModel: FeedViewModel<String>,
        novelId: Long,
        liked: Boolean,
    ) {
        viewModel.updateItems(NovelFeedItem::class.java) { item ->
            if (item.novel.id == novelId) item.withBookmarked(liked) else item
        }
    }

    private fun sendNovelLikedBroadcast(novelId: Long, liked: Boolean) {
        val intent = Intent(Params.LIKED_NOVEL).apply {
            // LIKED_NOVEL 契约里 id 走 int(NovelBean.id/CommonReceiver.getInt);小说 id 在 int 范围内。
            putExtra(Params.ID, novelId.toInt())
            putExtra(Params.IS_LIKED, liked)
        }
        // 用 app context(LBM 是 app 级单例):send 跑在网络回来后的协程里,不依赖 view 存活。
        LocalBroadcastManager.getInstance(Shaft.getContext()).sendBroadcast(intent)
    }

    private fun openNovelAuthor(novel: Novel) {
        novel.user?.id?.let { openUserActivity(it) }
    }

    private fun openNovelSeries(seriesId: Long) {
        startActivity(Intent(requireContext(), TemplateActivity::class.java).apply {
            putExtra(NovelSeriesFragment.ARG_SERIES_ID, seriesId)
            putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说系列")
        })
    }

    /** 封面点击看封面大图（对齐 NAdapter 的「图片详情」，取最大图，priority 同 ImageUrlsBean.getMaxImage）。 */
    private fun openCoverImage(novel: Novel) {
        val url = novel.image_urls?.let {
            it.original ?: it.large ?: it.medium ?: it.square_medium
        } ?: return
        startActivity(Intent(requireContext(), TemplateActivity::class.java).apply {
            putExtra(Params.URL, GlideUtil.getUrl(url).toStringUrl())
            putExtra(TemplateActivity.EXTRA_FRAGMENT, "图片详情")
        })
    }

    /** 爱心长按弹「按标签收藏」sheet（对齐 NAdapter；接收方按 int ILLUST_ID 读，沿用 legacy 语义）。 */
    private fun openNovelTagBookmark(novel: Novel) {
        SelectTagBottomSheet.show(
            this,
            novel.id.toInt(),
            Params.TYPE_NOVEL,
            novel.tags.orEmpty().mapNotNull { it.name }.toTypedArray(),
        )
    }
}
