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
import androidx.lifecycle.ViewModel
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.helper.IllustNovelFilter
import ceui.pixiv.ui.bookmark.SelectTagBottomSheet
import ceui.lisa.databinding.RecyNovelBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.api.Client
import ceui.loxia.Novel
import ceui.pixiv.actions.PixivActions
import ceui.pixiv.chat.base.viewModels
import ceui.pixiv.feeds.FeedCell
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedNovelSkeletonView
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSkeletonView
import ceui.pixiv.feeds.FeedViewModel
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.ui.novel.NovelSeriesFragment
import ceui.pixiv.ui.novel.reader.export.ExportFormat
import ceui.pixiv.ui.novel.reader.export.NovelExportManager
import ceui.pixiv.ui.novel.reader.ui.ExportFormatCallback
import ceui.pixiv.ui.novel.reader.ui.ExportSheet
import ceui.pixiv.ui.recommend.bindTrendingScore
import ceui.pixiv.ui.task.BatchDownloadNovelsTask
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
import ceui.pixiv.ui.navigation.TemplateRoute

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
private const val NOVEL_CARD_EXPORT_TAG = "NovelCardExportSheet"

/** 小说列表页“下载这一篇”的待确认请求，跨旋转保留在 Fragment ViewModel 中。 */
class NovelDownloadRequestViewModel : ViewModel() {
    var pendingNovel: Novel? = null
}

/**
 * 小说列表页的共享基类（对齐插画侧 [IllustFeedFragment]）。子类只声明数据源
 *（feedViewModels + mapper 产出 [NovelFeedItem]）；本类统一提供主力小说卡（recy_novel）：
 *
 * - 全程 [Novel] data class：收藏走 [ceui.pixiv.actions.PixivActions] 的持久化队列，
 *   跳转走 [DetailFeedSupport] 的 openNovelDetail/openUserActivity，标签流 [ceui.pixiv.widgets.V3TagFlowView]
 *   直接吃 [ceui.loxia.Tag]——不并存 legacy 可变 bean、不做 gson 往返；
 * - 收藏：乐观切态 + 收藏后自动关注作者(isAutoFollowAfterStar)，私密收藏设置 / 埋点 / RateApp /
 *   失败回滚都在队列侧统一处理，并收发 LIKED_NOVEL 广播与其它小说列表双向同步收藏态；
 * - 点击语义：卡片开小说详情 / 封面看封面大图 / 头像·作者进画师页 / 系列进小说系列页 /
 *   爱心长按进「按标签收藏」；
 * - 收藏态只有 is_bookmarked / total_bookmarks 变时走局部重绑 payload，不重跑 Glide(对齐插画卡)；
 * - LinearLayoutManager 竖向列表；条目是无界平铺（#1038），间距由 recy_novel 自身上下
 *   padding 承担，不加 ItemDecoration。
 *
 * 卡片布局与全部交互语义源自 legacy `NAdapter`（迁移时逐条对齐）。**该类已随最后一个调用方
 * 一起删除**（见「NAdapter 三个页面全部迁 feeds」那次提交），要考古去 git 历史，别在工作区找。
 */
abstract class NovelFeedFragment(
    @LayoutRes contentLayoutId: Int = ceui.pixiv.feeds.R.layout.fragment_feed,
) : FeedFragment(contentLayoutId), ExportFormatCallback {

    abstract override val feedViewModel: FeedViewModel<String>

    private val novelDownloadRequest by viewModels { NovelDownloadRequestViewModel() }

    /**
     * 小说卡“下载这一篇”：有默认格式直接下，设置为“每次询问”时弹 [ExportSheet]。
     * 请求放在 Fragment ViewModel 中，旋转后由新实例的 [onExportFormatChosen] 继续执行。
     */
    fun startNovelDownload(novel: Novel) {
        val format = NovelExportManager.resolveConfiguredFormat()
        if (format != null) {
            startNovelDownload(novel, format)
        } else {
            novelDownloadRequest.pendingNovel = novel
            ExportSheet().show(childFragmentManager, NOVEL_CARD_EXPORT_TAG)
        }
    }

    override fun onExportFormatChosen(format: ExportFormat) {
        finishNovelCardDownload(format)
    }

    final override fun onExportFormatChosen(format: ExportFormat, sourceTag: String?) {
        if (sourceTag == NOVEL_CARD_EXPORT_TAG) {
            // NovelTextFragment 同时展示相关推荐卡；不能让它的详情导出回调吞掉卡片请求。
            finishNovelCardDownload(format)
        } else {
            onExportFormatChosen(format)
        }
    }

    private fun finishNovelCardDownload(format: ExportFormat) {
        val novel = novelDownloadRequest.pendingNovel ?: return
        novelDownloadRequest.pendingNovel = null
        startNovelDownload(novel, format)
    }

    private fun startNovelDownload(novel: Novel, format: ExportFormat) {
        BatchDownloadNovelsTask(
            activity = requireActivity(),
            novels = listOf(novel),
            format = format,
            onFinished = { failures ->
                if (isAdded) {
                    Common.showToast(
                        if (failures.isEmpty()) {
                            getString(R.string.batch_download_all_ok)
                        } else {
                            getString(R.string.batch_download_some_failed, failures.size)
                        }
                    )
                }
            },
        )
    }

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
        observeMuteRevision()
    }

    /**
     * 竖向小说列表的骨架图长得像 recy_novel（左封面 + 右标题/系列/作者 + 标签流），不是瀑布流那种
     * 等宽块——基类默认只给 StaggeredGridLayoutManager 出骨架，小说列表是 Linear，得自己给。
     */
    override fun onCreateSkeletonView(layoutManager: RecyclerView.LayoutManager): FeedSkeletonView? {
        return FeedNovelSkeletonView(requireContext())
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(novelCardRenderer())
    }

    /**
     * 主力小说卡（recy_novel）。收藏爱心的乐观翻色在点击处当帧完成（异步 updateItems 只是
     * 落地态兜底），封面/头像用 Glide 加载，recycle 清图避免复用错图。收藏态变更只局部重绑
     * 爱心 + 收藏数(changePayload/bindPayloads),不重跑封面 Glide。
     *
     * [showTags]：本页是否渲染标签流（issue #1005 小说详情的区块卡不展示标签，避免区块过长）。
     * true 时仍受用户的「显示标签」设置约束。
     */
    protected fun novelCardRenderer(showTags: Boolean = true) = feedRenderer<NovelFeedItem, RecyNovelBinding>(
        inflate = RecyNovelBinding::inflate,
        create = { cell ->
            cell.binding.root.setOnClick {
                val tapped = cell.itemOrNull ?: return@setOnClick
                // 已屏蔽的卡：点一下 = 取消屏蔽，再点才打开（对齐插画卡）。不直接打开——
                // 否则「屏蔽」等于没屏蔽，手一滑就把刚盖住的东西整屏铺开了。
                unmuteOr(tapped.novel) { openNovelDetail(tapped.novel.id) }
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
                val pressed = cell.itemOrNull ?: return@OnLongClickListener false
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                showNovelCardMenu(pressed)
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
            // 封面 / 头像 / 作者名都有独立点击，屏蔽态同样是先取消屏蔽——否则点封面会直接
            // 打开未模糊的大图、点作者会进作者页，等于把刚盖住的东西整屏铺开。
            cell.binding.cover.setOnClick {
                val tapped = cell.itemOrNull ?: return@setOnClick
                unmuteOr(tapped.novel) { openCoverImage(tapped.novel) }
            }
            cell.binding.userHead.setOnClick {
                val tapped = cell.itemOrNull ?: return@setOnClick
                unmuteOr(tapped.novel) { openNovelAuthor(tapped.novel) }
            }
            cell.binding.author.setOnClick {
                val tapped = cell.itemOrNull ?: return@setOnClick
                unmuteOr(tapped.novel) { openNovelAuthor(tapped.novel) }
            }
            cell.binding.like.setOnClick { toggleNovelLike(cell) }
            cell.binding.like.setOnLongClickListener {
                openNovelTagBookmark(cell.item.novel)
                true
            }
        },
        recycle = { cell ->
            // 淡入淡出可能正跑到一半就被回收：连 animator 一起停掉并把 alpha 归位，
            // 否则复用到下一条目时粒子层带着半透明残值出场（对齐插画卡）。
            cell.binding.spoilerParticles.animate().cancel()
            cell.binding.spoilerParticles.alpha = 1f
            cell.binding.spoilerParticles.setParticleAnimationRunning(false)
            novelGlide.clear(cell.binding.cover)
            cell.binding.cover.tag = null
            novelGlide.clear(cell.binding.userHead)
        },
        // detach 停了逐帧模拟，就必须有对称的 attach 把它开回来：RecyclerView 的 mCachedViews
        // 里的 holder 是「只 detach、不重新 onBind」的，滚出去一点点再滚回来不会走绑定，
        // 少了这个钩子 requestedRunning 永远停在 false，粒子层直接一帧都不画（对齐插画卡）。
        attach = { cell ->
            val spoilered = cell.itemOrNull?.let {
                NovelMuteStore.isMuted(it.novel.id) || IllustNovelFilter.shouldBlurAi(it.novel)
            }
            if (spoilered == true) {
                cell.binding.spoilerParticles.setParticleAnimationRunning(true)
            }
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
                    val spoilered = NovelMuteStore.isMuted(novel.id) || IllustNovelFilter.shouldBlurAi(novel)
                    loadNovelCover(cell.binding.cover, novel, spoilered)
                    renderSpoilerParticles(cell.binding.spoilerParticles, show = spoilered, animate = true)
                    // 掩码涉及多个 view（文字换占位条/次级信息隐藏），跟全量绑定共用同一分支。
                    bindNovelCardContent(cell.binding, novel, cell.item.trendingScore, spoilered, showTags)
                }
                true
            } else {
                false
            }
        },
    ) { cell -> bindNovelCard(cell, showTags) }

    private fun bindNovelCard(cell: FeedCell<NovelFeedItem, RecyNovelBinding>, showTags: Boolean) {
        val b = cell.binding
        val novel = cell.item.novel
        // 打码与否的真源是本地屏蔽名单 + AI 屏蔽强度，bind 时现读：其它页面改了屏蔽/设置，
        // 本页滑动复用一次就跟上（条目本身不带这个状态，见 PAYLOAD_NOVEL_SPOILER_CHANGED；
        // 没被回收的卡由 observeMuteRevision 补绑）。
        val spoilered = NovelMuteStore.isMuted(novel.id) || IllustNovelFilter.shouldBlurAi(novel)
        loadNovelCover(b.cover, novel, spoilered)
        renderSpoilerParticles(b.spoilerParticles, show = spoilered, animate = false)
        bindNovelCardContent(b, novel, cell.item.trendingScore, spoilered, showTags)
    }

    /** 内容区绑定的两分支入口：屏蔽态走掩码（占位条 + 隐藏次级信息），否则走正常文本。 */
    private fun bindNovelCardContent(
        b: RecyNovelBinding,
        novel: Novel,
        trendingScore: Float?,
        spoilered: Boolean,
        showTags: Boolean,
    ) {
        applyNovelSpoilerMask(b, novel, masked = spoilered)
        if (!spoilered) {
            renderNovelCardText(b, novel, trendingScore, showTags)
        }
    }

    private fun renderNovelCardText(
        b: RecyNovelBinding,
        novel: Novel,
        trendingScore: Float?,
        showTags: Boolean,
    ) {
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

        // 标签流：尊重「显示标签」设置，关时喂空列表折叠。compact + 去 # 前缀；译名默认不
        // 显示（#1038），可由「小说列表显示标签译文」恢复（#1089）。「标签折叠」开启时
        // 超 6 个折叠成「+N」，关闭时 maxTags=-1 全量展示；searchIndex=1 跳搜索页「小说」tab。
        val tags = if (showTags && Shaft.sSettings.isShowNovelCardTags()) novel.tags.orEmpty() else emptyList()
        b.novelTag.compact = true
        b.novelTag.searchIndex = 1
        b.novelTag.showHashPrefix = false
        b.novelTag.showTranslation = Shaft.sSettings.isShowNovelCardTagTranslations()
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
    private fun applyNovelSpoilerMask(b: RecyNovelBinding, novel: Novel, masked: Boolean) {
        if (masked) {
            // 系列这一行的可见性平时由 renderNovelCardText 按有无系列切；屏蔽分支不管的话，
            // 同一本小说长什么样就取决于这个 holder 上一条是谁——复用到有系列的卡就凭空多一根条。
            val series = novel.series
            b.series.isVisible = series != null && !series.title.isNullOrEmpty()
            // 屏蔽态下系列不再跳系列页，但也不能只 setOnClickListener(null)：那不会把
            // clickable 复位回 false，view 会继续吃掉触摸事件又什么都不做，root 的 unmuteOr
            // 收不到，表现就是「点这根条没反应」。跟卡片其它区域一样先取消屏蔽。
            //
            // 用裸 setOnClickListener 而不是 setOnClick：后者每次调用都要
            // AnimatorInflater.loadStateListAnimator 现造一个 StateListAnimator，而这里在
            // 每次绑定的热路径上；一根占位条也不需要按压渐隐。
            b.series.setOnClickListener { unmuteOr(novel) }
        }
        listOf(b.title, b.author, b.series).forEach { tv ->
            if (masked) {
                // 单个空格 + wrap_content：占位条高度自然等于该行文字行高。
                // 不能动 TextView.height —— 那改的是 min/maxHeight，复位时会钳死正常文本。
                tv.text = " "
                // 每个 view 必须拿到独立的 Drawable 实例：bounds 和 callback 都存在实例上，
                // 共用一份会被最后布局的那个 view 改掉尺寸（title 是整行宽，author 还要
                // 减掉头像和日期）。setBackgroundResource 走 Resources 层，ConstantState
                // 仍是共享的，不多占内存。
                tv.setBackgroundResource(R.drawable.bg_novel_spoiler_bar)
            } else {
                tv.background = null
            }
        }
        listOf(b.date, b.bookmarkCount, b.howManyWord, b.badgeAi, b.trendingScore, b.novelTag, b.userHead, b.like)
            .forEach { it.isVisible = !masked }
    }

    /**
     * 打码态下任何「想点开内容」的入口都先取消屏蔽（卡片本身 / 封面大图 / 头像 / 作者名 /
     * 系列条），否则屏蔽卡上点封面会直接打开未模糊的原图、点作者会进作者页。
     *
     * 取消屏蔽 = 删掉那条持久化记录，与长按菜单里那一项等价（对齐插画卡）。这五个点击区
     * 任何一个都能误触，误触的代价就是那条屏蔽记录没了。
     *
     * [action] 缺省为空：打码态下的占位条只需要「点一下取消屏蔽」，正常态下没有别的行为。
     */
    private fun unmuteOr(novel: Novel, action: () -> Unit = {}) {
        if (NovelMuteStore.isMuted(novel.id)) {
            setNovelMuted(novel, false)
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
        val url = GlideUtil.getUrl(novel.resolvedCoverUrl())
        val width = 80.ppppx
        val height = 119.ppppx
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
     * 开关某本小说的屏蔽：写 [NovelMuteStore]（内存当帧生效、Room 的 `tag_mute_table` 异步落地，
     * 于是「屏蔽记录」页里能看到这一条），再让屏幕上那张卡当帧换成模糊图 + 粒子（或还原）。
     *
     * 长按菜单里的「屏蔽 / 取消屏蔽此作品」和**点一下已打码的卡**（[unmuteOr]，= 取消屏蔽）
     * 都走这里。屏蔽态只有「在不在名单里」一种——理由见 [MutedWorkStore] 的类注释。
     *
     * 收 [Novel] 而不是裸 id：屏蔽方向要把整本小说序列化进记录（字段名与 Novel 对得上，
     * 「屏蔽记录」页直接按 Novel 解出来画封面/标题/作者）。取消方向用不上。
     */
    internal fun setNovelMuted(novel: Novel, muted: Boolean) {
        if (!NovelMuteStore.setMuted(novel.id, muted) { novel }) return
        rebindNovelCard(novel.id)
    }

    /**
     * 让屏上那张卡当帧换成模糊图 + 粒子（或还原）。
     *
     * 走 `notifyItemChanged(payload)` 而不是 [FeedViewModel] 的条目变更：屏蔽态不在条目上，
     * 列表数据一个字节没变，没有 diff 可跑。这条通知是纯 UI 的一次性重绑，被后续 submitList
     * 覆盖也无所谓——全量绑定同样现读名单。
     *
     * 只管本页那一张；别的页由 [observeMuteRevision] 跟上。顺手记下版本号，免得订阅回调为自己
     * 刚做的这次变更再空跑一遍整屏。
     */
    private fun rebindNovelCard(novelId: Long) {
        lastMuteRevision = NovelMuteStore.revision
        val adapter = feedAdapter ?: return
        val position = adapter.currentList.indexOfFirst {
            it is NovelFeedItem && it.novel.id == novelId
        }
        if (position >= 0) {
            adapter.notifyItemChanged(position, PAYLOAD_NOVEL_SPOILER_CHANGED)
        }
    }

    /** 本页卡片上一次渲染时的 [NovelMuteStore.revision]，见 [observeMuteRevision]。 */
    private var lastMuteRevision = NovelMuteStore.revision

    /**
     * 订阅屏蔽名单的版本号，变了就给本页所有小说卡补一次 spoiler 重绑。
     * 理由（含「为什么不能写成 onResume 里比版本号」）与插画侧逐字相同，
     * 见 [IllustFeedFragment.observeMuteRevision]；逐段发 payload 的理由见
     * [IllustFeedFragment.rebindAllIllustCards]。
     */
    private fun observeMuteRevision() {
        NovelMuteStore.revisionLive.observe(viewLifecycleOwner) { revision ->
            if (revision == lastMuteRevision) return@observe
            lastMuteRevision = revision
            rebindAllNovelCards()
        }
    }

    private fun rebindAllNovelCards() {
        val adapter = feedAdapter ?: return
        val items = adapter.currentList
        var start = -1
        items.forEachIndexed { index, item ->
            if (item is NovelFeedItem) {
                if (start < 0) start = index
            } else if (start >= 0) {
                adapter.notifyItemRangeChanged(start, index - start, PAYLOAD_NOVEL_SPOILER_CHANGED)
                start = -1
            }
        }
        if (start >= 0) {
            adapter.notifyItemRangeChanged(
                start, items.size - start, PAYLOAD_NOVEL_SPOILER_CHANGED,
            )
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
            putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.NOVEL_SERIES.key)
        })
    }

    /** 封面点击看封面大图（对齐 NAdapter 的「图片详情」，取最大图，priority 同 ImageUrlsBean.getMaxImage）。 */
    private fun openCoverImage(novel: Novel) {
        val url = novel.image_urls?.let {
            it.original ?: it.large ?: it.medium ?: it.square_medium
        } ?: return
        startActivity(Intent(requireContext(), TemplateActivity::class.java).apply {
            putExtra(Params.URL, GlideUtil.getUrl(url).toStringUrl())
            putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.IMAGE_DETAIL.key)
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
