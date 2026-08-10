package ceui.pixiv.ui.common

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import androidx.annotation.LayoutRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
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
import ceui.pixiv.actions.PixivActions
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
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions.bitmapTransform
import jp.wasabeef.glide.transformations.BlurTransformation
import timber.log.Timber

/** 收藏态局部重绑的 payload 标记（按引用识别）。 */
private val PAYLOAD_NOVEL_BOOKMARK = Any()

/** 屏蔽态局部重绑的 payload 标记（对齐插画侧 PAYLOAD_ILLUST_SPOILER_CHANGED）。 */
private val PAYLOAD_NOVEL_SPOILER_CHANGED = Any()

/** 封面 Glide 请求去重 key（存在 ImageView.tag 上）：url + 请求尺寸 + 是否模糊。 */
private data class NovelImageRequestKey(
    val cacheKey: String?,
    val width: Int,
    val height: Int,
    val blurred: Boolean,
)

private const val NOVEL_SPOILER_BLUR_RADIUS = 25
private const val NOVEL_SPOILER_BLUR_SAMPLING = 3

/**
 * 小说列表页的共享基类（对齐插画侧 [IllustFeedFragment]）。子类只声明数据源
 *（feedViewModels + mapper 产出 [NovelFeedItem]）；本类统一提供主力小说卡（recy_novel）：
 *
 * - 全程 loxia [Novel] data class：收藏走 [ceui.pixiv.actions.PixivActions] 的持久化队列，
 *   跳转走 [DetailFeedSupport] 的 openNovelDetail/openUserActivity，标签流 [ceui.pixiv.widgets.V3TagFlowView]
 *   直接吃 loxia [ceui.loxia.Tag]——不并存 legacy 可变 bean、不做 gson 往返；
 * - 收藏：乐观切态 + 收藏后自动关注作者(isAutoFollowAfterStar)，私密收藏设置 / 埋点 / RateApp /
 *   失败回滚都在队列侧统一处理，并收发 LIKED_NOVEL 广播与其它小说列表双向同步收藏态；
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
            cell.binding.root.setOnClick {
                val tapped = cell.itemOrNull ?: return@setOnClick
                // 已屏蔽的卡先「揭开」再看（对齐插画卡）：否则「屏蔽」等于没屏蔽，
                // 手一滑就把刚盖住的东西整屏铺开了。取消屏蔽的另一个入口是长按菜单同一项。
                revealOr(tapped.novel.id) { openNovelDetail(tapped.novel.id) }
            }
            // 长按 = 批量操作入口（issue #974），语义对齐插画卡的长按菜单。
            // 挂在基类的 renderer 上，所有小说列表页（推荐 / 收藏 / 关注 / 用户 / 搜索…）一起有。
            //
            // **必须逐个可点子 view 都挂，只挂 root 是不够的**：子 view 一旦 clickable 就会
            // 在 ACTION_DOWN 时把事件吃掉，父 view 根本收不到长按；而 clickable 但不
            // longClickable 的 view 会把这次长按在抬手时**当成一次普通点击**处理 ——
            // 表现就是「想长按封面/作者/系列，结果直接跳走了」（真机上按在系列名上必现）。
            // like 不挂：它自己的长按是「按标签收藏」，不能被顶掉。
            val onCardLongPress = View.OnLongClickListener { v ->
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                showNovelCardMenu(cell.item)
                true
            }
            listOf(
                cell.binding.root,
                cell.binding.cover,
                cell.binding.userHead,
                cell.binding.author,
                cell.binding.series,
                cell.binding.novelTag,
            ).forEach { it.setOnLongClickListener(onCardLongPress) }
            // 封面 / 头像 / 作者名都有独立点击，屏蔽态同样要先揭开——否则点封面会直接
            // 打开未模糊的大图、点作者会进作者页，等于把刚盖住的东西整屏铺开。
            cell.binding.cover.setOnClick {
                val tapped = cell.itemOrNull ?: return@setOnClick
                revealOr(tapped.novel.id) { openCoverImage(tapped.novel) }
            }
            cell.binding.userHead.setOnClick {
                val tapped = cell.itemOrNull ?: return@setOnClick
                revealOr(tapped.novel.id) { openNovelAuthor(tapped.novel) }
            }
            cell.binding.author.setOnClick {
                val tapped = cell.itemOrNull ?: return@setOnClick
                revealOr(tapped.novel.id) { openNovelAuthor(tapped.novel) }
            }
            cell.binding.like.setOnClick { toggleNovelLike(cell) }
            cell.binding.like.setOnLongClickListener {
                openNovelTagBookmark(cell.item.novel)
                true
            }
        },
        recycle = { cell ->
            novelGlide.clear(cell.binding.cover)
            cell.binding.cover.tag = null
            novelGlide.clear(cell.binding.userHead)
        },
        detach = { cell ->
            cell.binding.spoilerParticles.setParticleAnimationRunning(false)
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
            val known = payloads.all {
                it === PAYLOAD_NOVEL_BOOKMARK || it === PAYLOAD_NOVEL_SPOILER_CHANGED
            }
            if (known) {
                if (payloads.any { it === PAYLOAD_NOVEL_BOOKMARK }) {
                    val novel = cell.item.novel
                    renderNovelLike(cell.binding.like, novel.is_bookmarked == true)
                    cell.binding.bookmarkCount.text = (novel.total_bookmarks ?: 0).toString()
                }
                if (payloads.any { it === PAYLOAD_NOVEL_SPOILER_CHANGED }) {
                    val novel = cell.item.novel
                    val spoilered = NovelSpoilerStore.isSpoilered(novel.id)
                    loadNovelCover(cell.binding.cover, novel, spoilered)
                    renderSpoilerParticles(cell.binding.spoilerParticles, show = spoilered, animate = true)
                    // 掩码涉及多个 view（文字换占位条/次级信息隐藏），跟全量绑定共用同一分支。
                    bindNovelCardContent(cell.binding, novel, cell.item.trendingScore, spoilered)
                }
                true
            } else {
                false
            }
        },
    ) { cell -> bindNovelCard(cell) }

    private fun bindNovelCard(cell: FeedCell<NovelFeedItem, RecyNovelBinding>) {
        val b = cell.binding
        val novel = cell.item.novel
        // 屏蔽态的真源是本地名单（NovelSpoilerStore），bind 时现读：其它页面屏蔽了同一本
        // 小说，本页滑动复用一次就跟上（条目本身不带这个状态，见 PAYLOAD_NOVEL_SPOILER_CHANGED）。
        val spoilered = NovelSpoilerStore.isSpoilered(novel.id)
        loadNovelCover(b.cover, novel, spoilered)
        renderSpoilerParticles(b.spoilerParticles, show = spoilered, animate = false)
        bindNovelCardContent(b, novel, cell.item.trendingScore, spoilered)
    }

    /** 内容区绑定的两分支入口：屏蔽态走掩码（占位条 + 隐藏次级信息），否则走正常文本。 */
    private fun bindNovelCardContent(
        b: RecyNovelBinding,
        novel: Novel,
        trendingScore: Float?,
        spoilered: Boolean,
    ) {
        applyNovelSpoilerMask(b, masked = spoilered)
        if (!spoilered) {
            renderNovelCardText(b, novel, trendingScore)
        }
    }

    private fun renderNovelCardText(b: RecyNovelBinding, novel: Novel, trendingScore: Float?) {
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
        b.trendingScore.bindTrendingScore(trendingScore)
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

        novel.user?.let { novelGlide.load(GlideUtil.getHead(it)).into(b.userHead) }

        renderNovelLike(b.like, novel.is_bookmarked == true)
    }

    /**
     * 屏蔽掩码开关（遮盖式伪模糊，全 API 一致）。
     *
     * [masked]=true：标题/作者/系列文字换成圆角占位条，日期/收藏数/字数/AI 角标/热度 pill/
     * 标签/头像/爱心全部隐藏；
     * [masked]=false：把这些复位（正常分支随后会重新赋真实文本与可见性）。
     * 封面仍由 [loadNovelCover] 出模糊位图，整卡粒子层由调用方铺。
     */
    private fun applyNovelSpoilerMask(b: RecyNovelBinding, masked: Boolean) {
        listOf(b.title, b.author, b.series).forEach { tv ->
            if (masked) {
                // 单个空格 + wrap_content：占位条高度自然等于该行文字行高。
                // 不能动 TextView.height —— 那改的是 min/maxHeight，复位时会钳死正常文本。
                tv.text = " "
                tv.background = spoilerBarDrawable
            } else {
                tv.background = null
            }
        }
        listOf(b.date, b.bookmarkCount, b.howManyWord, b.badgeAi, b.trendingScore, b.novelTag, b.userHead, b.like)
            .forEach { it.isVisible = !masked }
        if (masked) {
            b.series.setOnClickListener(null)
        }
    }

    /** 屏蔽占位条背景：shape 无状态，全卡复用同一份即可。 */
    private val spoilerBarDrawable by lazy {
        ContextCompat.getDrawable(requireContext(), R.drawable.bg_novel_spoiler_bar)
    }

    /**
     * 屏蔽态下任何「想点开内容」的入口都先揭开（卡片本身 / 封面大图 / 头像 / 作者名），
     * 否则屏蔽卡上点封面会直接打开未模糊的原图、点作者会进作者页。
     */
    private fun revealOr(novelId: Long, action: () -> Unit) {
        if (NovelSpoilerStore.isSpoilered(novelId)) {
            setNovelSpoilered(novelId, false)
        } else {
            action()
        }
    }

    /**
     * 封面图加载：屏蔽态直接让 Glide 出一张模糊位图（变换进 cacheKey，与原因各存各的，
     * 滚回来是缓存命中；不用 View 层模糊——那类做法在列表复用里每帧都要重算）。
     * 请求 key 存 tag，真没变时跳过这次重新加载，避免局部重绑时白发一次请求。
     */
    private fun loadNovelCover(cover: ImageView, novel: Novel, spoilered: Boolean) {
        val url = GlideUtil.getUrl(novel.coverUrl)
        val width = 90.ppppx
        val height = 134.ppppx
        val requestKey = NovelImageRequestKey(url?.cacheKey, width, height, spoilered)
        if (cover.tag == requestKey) return
        cover.tag = requestKey
        var request = novelGlide.load(url).override(width, height)
        if (spoilered) {
            request = request.apply(
                bitmapTransform(BlurTransformation(NOVEL_SPOILER_BLUR_RADIUS, NOVEL_SPOILER_BLUR_SAMPLING))
            )
        }
        request
            .placeholder(R.color.v3_surface_2)
            .error(R.color.v3_surface_2)
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(cover)
    }

    /**
     * 开关某本小说的「屏蔽」遮罩：写本地名单（[NovelSpoilerStore]），再让屏幕上那张卡当帧
     * 换成模糊图 + 粒子（或还原）。长按菜单和「点已屏蔽的卡揭开」都走这里。
     *
     * 走 `notifyItemChanged(payload)` 而不是 [FeedViewModel] 的条目变更：屏蔽态不在条目上，
     * 列表数据一个字节没变，没有 diff 可跑。这条通知是纯 UI 的一次性重绑，被后续 submitList
     * 覆盖也无所谓——全量绑定同样现读名单。
     */
    internal fun setNovelSpoilered(novelId: Long, spoilered: Boolean) {
        if (!NovelSpoilerStore.setSpoilered(novelId, spoilered)) return
        val adapter = feedAdapter ?: return
        val position = adapter.currentList.indexOfFirst {
            it is NovelFeedItem && it.novel.id == novelId
        }
        if (position >= 0) {
            adapter.notifyItemChanged(position, PAYLOAD_NOVEL_SPOILER_CHANGED)
        }
    }

    /** 未收藏=灰，已收藏=红（爱心图标 ic_like_illust_6 由布局给，这里只切 tint）。 */
    private fun renderNovelLike(button: ImageButton, liked: Boolean) {
        val color = if (liked) R.color.has_bookmarked else R.color.not_bookmarked
        button.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(button.context, color))
    }

    /** VM 里当前整表的小说条目。对齐 [IllustFeedFragment.currentIllustItems]。 */
    internal fun currentNovelItems(): List<NovelFeedItem> {
        return feedViewModel.uiState.value.items.filterIsInstance<NovelFeedItem>()
    }

    /** VM 里当前这条小说的最新条目（真源）；已被刷新挤掉则 null。对齐 [IllustFeedFragment.currentIllustItem]。 */
    private fun currentNovelItem(novelId: Long): NovelFeedItem? {
        return feedViewModel.uiState.value.items
            .firstOrNull { it is NovelFeedItem && it.novel.id == novelId } as? NovelFeedItem
    }

    /**
     * 收藏切换：点按当帧乐观翻心 + updateItems 落地(DiffUtil 局部重绑),写操作交给
     * [PixivActions] 的队列。
     *
     * 不再自己直发接口：同一本小说在阅读器 / 详情页那边已经是排队发的，这边同步直发的话，
     * 队列里压着的「取消收藏」会在几秒后把用户刚在列表里点的收藏又删掉。私密收藏设置、
     * 埋点、RateApp 一并由队列侧统一处理（埋点改到服务端确认之后才发）。
     *
     * 成功 toast 去掉了：这一刻请求还没发出去（队列可能正在冷却），报成功是骗用户；
     * 反馈由爱心本身承担。失败时队列会回滚 ObjectPool 并发 LIKED_NOVEL 广播，本列表的
     * receiver 收到后把条目拨回去（[withBookmarked] 幂等）。
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
        // 跨列表同步的 LIKED_NOVEL 广播由 PixivActions 内部发（与插画那支同一套写法）——
        // 此处不再自己补一遍：这里补过之后，从阅读器 / V3 详情流收藏同一本小说时别处的列表
        // 依然不同步，而那正是把广播收进门面里要解决的问题。
        PixivActions.setNovelBookmark(novel, target)
        // 收藏后自动关注作者。判重也交给门面：此前这里只看 novel.user.is_followed，池里刚
        // 关注过的作者判不出来，会重复发一次关注（插画那支一直是两边都查的）。
        if (target) {
            val user = novel.user
            PixivActions.autoFollowAuthor(user?.id, user?.is_followed)
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
