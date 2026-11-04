package ceui.pixiv.ui.novel

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.PaintDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.view.View
import android.view.ViewOutlineProvider
import androidx.annotation.ColorInt
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.text.HtmlCompat
import androidx.core.view.OneShotPreDrawListener
import androidx.core.view.ViewCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.CellNovelAuthorBinding
import ceui.lisa.databinding.CellNovelCaptionBinding
import ceui.lisa.databinding.CellNovelHeaderBinding
import ceui.lisa.databinding.CellNovelInfoBinding
import ceui.lisa.databinding.CellNovelSectionHeaderBinding
import ceui.lisa.databinding.CellNovelTagsBinding
import ceui.lisa.fragments.WebNovelParser
import ceui.lisa.helper.IllustNovelFilter
import ceui.lisa.helper.WebLinkHelper
import ceui.lisa.transformer.CpuBlurTransformation
import ceui.lisa.utils.Common
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.lisa.utils.SearchTypeUtil
import ceui.lisa.utils.ShareIllust
import ceui.loxia.Novel
import ceui.loxia.Series
import ceui.loxia.User
import ceui.pixiv.actions.FollowVisibility
import ceui.pixiv.actions.PixivActions
import ceui.pixiv.api.Client
import ceui.pixiv.cache.ObjectPool
import ceui.pixiv.feeds.FeedCell
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.FeedViewModel
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.ui.bookmark.SelectTagBottomSheet
import ceui.pixiv.ui.common.IllustCardActionReceiver
import ceui.pixiv.ui.common.NOVEL_URL_HEAD
import ceui.pixiv.ui.common.NovelActionReceiver
import ceui.pixiv.ui.common.NovelFeedItem
import ceui.pixiv.ui.common.findActionReceiverOrNull
import ceui.pixiv.ui.common.renderFollowPill
import ceui.pixiv.ui.detail.applyDetailPanelExpanded
import ceui.pixiv.ui.navigation.TemplateRoute
import ceui.pixiv.ui.novel.reader.NovelTextCache
import ceui.pixiv.ui.novel.reader.paginate.ContentParser
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.user.UserActionReceiver
import ceui.pixiv.utils.extractPixivId
import ceui.pixiv.utils.playToggleHaptic
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.color
import ceui.pixiv.witstudio.theme.dp
import ceui.pixiv.witstudio.theme.dpF
import ceui.pixiv.witstudio.theme.hairlinePx
import ceui.pixiv.witstudio.theme.pressScale
import ceui.pixiv.witstudio.theme.ripple
import ceui.pixiv.witstudio.theme.shape
import ceui.pixiv.witstudio.theme.v3Font
import com.bumptech.glide.RequestManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.text.NumberFormat
import androidx.core.net.toUri

// ── 从旧 holder 文件迁来的公共契约（同包，FQN 不变，外部 import 无需改）──────

interface NovelSeriesActionReceiver {
    fun onClickNovelSeries(sender: View, series: Series)
}

interface NovelActionsReceiver {
    fun onClickReadNovel(sender: View, novelId: Long)
    fun onClickNovelCover(sender: View, novelId: Long)
    fun onClickShareNovel(sender: View, novelId: Long)
    fun onClickNovelComments(sender: View, novelId: Long)
    fun onClickDownloadNovel(sender: View, novelId: Long)
    fun onLongClickDownloadNovel(sender: View, novelId: Long)
}

internal fun openTagBookmarkForNovel(sender: View, novel: Novel) {
    // 只有 View / Context，用 showFrom 解出宿主 FragmentActivity 弹 sheet
    // （原先是 startActivity 一张从右侧 push 进来的整页）。
    val tagNames = novel.tags.orEmpty().mapNotNull { it.name }.toTypedArray()
    SelectTagBottomSheet.showFrom(sender.context, novel.id, Params.TYPE_NOVEL, tagNames)
}

// ── FeedItem 模型（都以 novelId 为身份；实际小说数据由渲染器观察 ObjectPool 取）─────

data class NovelHeaderFeedItem(val novelId: Long) : FeedItem {
    override val feedKey: Any get() = novelId
}

data class NovelCaptionFeedItem(val novelId: Long) : FeedItem {
    override val feedKey: Any get() = novelId
}

data class NovelTagsFeedItem(val novelId: Long) : FeedItem {
    override val feedKey: Any get() = novelId
}

/** 「信息」区块（取代旧「作品档案」chip 墙）。 */
data class NovelInfoFeedItem(val novelId: Long) : FeedItem {
    override val feedKey: Any get() = novelId
}

/**
 * 「作者」区块。[user] 只是兜底快照：关注态以 ObjectPool 里的同一位用户为准（关注 / 取关会写穿池子），
 * 下拉刷新换新快照也不会让 DiffUtil 误判内容变了 —— 渲染器本来就不读快照的关注态。
 */
data class NovelAuthorFeedItem(val user: User) : FeedItem {
    override val feedKey: Any get() = user.id
}

// ── 懒加载区块（issue #1005，对齐插画详情的作者往期/相关作品）────────────────

/**
 * 小说详情页的懒加载区块：滚到可见才拉，触发编排复用插画侧的
 * [ceui.pixiv.ui.detail.SectionLoader]（三层失败恢复见其 KDoc）。区块内容直接以
 * [NovelFeedItem] 平铺进主列表（一行一部、主力小说卡），不做嵌套横滑——
 * issue 里「像小卡一样一个作品一栏」指的就是这张卡。
 */
enum class NovelDetailSection {

    /** 作者的其他小说。作者一部其它作品都没有时整个区块头撤下（见 [fillNovelSection]）。 */
    AUTHOR_WORKS {
        override suspend fun load(novelId: Long, vm: FeedViewModel<String>) {
            // userId 从区块头条目自身读（对齐插画侧）：详情可能没留在 ObjectPool，回池取会拿不到
            val userId = vm.uiState.value.items
                .filterIsInstance<NovelSectionHeaderItem>()
                .firstOrNull { it.section == AUTHOR_WORKS }
                ?.userId ?: return
            if (userId <= 0) {
                fillNovelSection(vm, AUTHOR_WORKS, emptyList())
                return
            }
            fillNovelSection(vm, AUTHOR_WORKS, fetchNovelAuthorWorks(userId, novelId))
        }
    },

    /** 相关小说：web 端 recommend 拿 id，app-api detail 补水。 */
    RELATED {
        override suspend fun load(novelId: Long, vm: FeedViewModel<String>) {
            fillNovelSection(vm, RELATED, fetchNovelRelated(novelId))
        }
    };

    abstract suspend fun load(novelId: Long, vm: FeedViewModel<String>)
}

/** 「区块首次可见」信号的接收方（[NovelTextFragment] 转给 SectionLoader）。 */
interface NovelSectionReceiver {
    fun onNovelSectionVisible(section: NovelDetailSection)
}

data class NovelSectionHeaderItem(
    val section: NovelDetailSection,
    /** 仅 AUTHOR_WORKS 用：作者 id / 名字随条目自带，不依赖 ObjectPool（对齐插画侧）。 */
    val userId: Long = 0L,
    val authorName: String = "",
    /** null=未加载（可见时触发懒加载并转圈），false=拉过但为空，true=有内容。 */
    val state: Boolean? = null,
) : FeedItem {
    override val feedKey: Any get() = section
}

/**
 * 区块预览条数。往期 3 部是 issue #1005 报告人的建议；相关作品从建议的 5 部缩到
 * 3 部——它没有原生端点，每张卡都要一次 detail 补水，讨论后按请求量收敛。
 */
private const val AUTHOR_WORKS_PREVIEW_COUNT = 3
private const val RELATED_PREVIEW_COUNT = 3

/**
 * 把区块内容一次性落回列表：区块头翻态 + 卡片插到头后面，单次 [FeedViewModel.mutateItems]
 * 提交。区块头已填过（state != null）直接放弃——下拉刷新会整代换新条目，不存在补第二次。
 * 跨区块按 novel.id 去重：同一部小说既是作者往期又是相关时只留先到的那张，
 * 重复 feedKey 会破坏 DiffUtil 的「列表内身份唯一」前置。
 */
private fun fillNovelSection(
    vm: FeedViewModel<String>,
    section: NovelDetailSection,
    works: List<NovelFeedItem>,
) {
    vm.mutateItems { existing ->
        val headerIdx = existing.indexOfFirst {
            it is NovelSectionHeaderItem && it.section == section
        }
        if (headerIdx < 0) return@mutateItems existing
        val header = existing[headerIdx] as NovelSectionHeaderItem
        if (header.state != null) return@mutateItems existing
        val seen = existing.filterIsInstance<NovelFeedItem>().mapTo(HashSet()) { it.novel.id }
        val fresh = works.filter { seen.add(it.novel.id) }
        val result = ArrayList<FeedItem>(existing.size + fresh.size)
        result.addAll(existing)
        if (section == NovelDetailSection.AUTHOR_WORKS && fresh.isEmpty()) {
            // 作者没有其它小说：留一个空区块头没有意义，整块撤下
            result.removeAt(headerIdx)
        } else {
            result[headerIdx] = header.copy(state = fresh.isNotEmpty())
            result.addAll(headerIdx + 1, fresh)
        }
        result
    }
}

/**
 * 作者的其他小说。skipMuteUserFilter：用户主动点开的就是这位作者的作品，
 * 沿用「作者本人页让步」口径（见 [NovelFeedItem.of] 的 KDoc）。
 */
private suspend fun fetchNovelAuthorWorks(userId: Long, excludeNovelId: Long): List<NovelFeedItem> {
    return Client.appApi.getUserCreatedNovels(userId).novels
        .asSequence()
        .filter { it.id != excludeNovelId }
        .mapNotNull { NovelFeedItem.of(it, skipMuteUserFilter = true) }
        .take(AUTHOR_WORKS_PREVIEW_COUNT)
        .toList()
}

/**
 * 相关小说。web 端 recommend 只取 id（为什么不用它的卡片数据见
 * [ceui.pixiv.api.PixivWebApi.getNovelRecommendInit] 的 KDoc），detail 并发补水：
 * 单条失败（已删除/不可见）跳过；推荐非空而补水全军覆没则抛出去走 SectionLoader
 * 的自动重试——那是网络挂了，不是「没有相关作品」。
 *
 * +2 是补水备胎：id 里可能混着已删除 / R18 被过滤 / 命中屏蔽设定的，多拿两个
 * 尽量凑满 [RELATED_PREVIEW_COUNT] 张卡；并发上限即 3+2=5 个 detail 请求。
 */
private suspend fun fetchNovelRelated(novelId: Long): List<NovelFeedItem> = coroutineScope {
    val ids = Client.webApi.getNovelRecommendInit(novelId, limit = RELATED_PREVIEW_COUNT + 2)
        .body?.novels.orEmpty()
        .asSequence()
        .filter { it.isMasked != true }
        .mapNotNull { it.id?.toLongOrNull() }
        .filter { it != novelId }
        .take(RELATED_PREVIEW_COUNT + 2)
        .toList()
    if (ids.isEmpty()) return@coroutineScope emptyList()
    val hydrated = ids.map { id ->
        async {
            // 单条补水失败只丢这一条，但必须放行 CancellationException——
            // 吞掉它会把「视图销毁取消」误判成「这条补水失败」（本仓硬约定）
            runCatching { Client.appApi.getNovel(id).novel?.also { ObjectPool.update(it) } }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
        }
    }.awaitAll().filterNotNull()
    if (hydrated.isEmpty()) error("相关小说补水全部失败 novelId=$novelId ids=${ids.size}")
    hydrated.mapNotNull { NovelFeedItem.of(it) }.take(RELATED_PREVIEW_COUNT)
}

// ── FeedSource：单页，无分页（一篇小说的固定卡 + 两个懒加载区块头）─────────

class NovelTextFeedSource(private val novelId: Long) : FeedSource<String> {

    /** 首次 load 后置 true；本源单页无翻页，之后每次 load(null) 都是下拉刷新。 */
    private var hasLoadedOnce = false

    override suspend fun load(cursor: String?): FeedPage<String> {
        val novel = Client.appApi.getNovel(novelId).novel?.also {
            ObjectPool.update(it)
            it.user?.let { user -> ObjectPool.update(user) }
        } ?: ObjectPool.get<Novel>(novelId).value
        if (cursor == null && hasLoadedOnce) refreshNovelText()
        hasLoadedOnce = true
        // 顺序同 V3「小说详情」：头卡（hero + 统计 + 系列）→ 简介 → 标签 → 信息 → 作者（+ 其他作品）
        // → 相关作品。简介 / 标签只在有内容时出现：一个只有标题的空区块不如不放。
        val items = mutableListOf<FeedItem>()
        items.add(NovelHeaderFeedItem(novelId))
        if (!novel?.caption.isNullOrBlank()) items.add(NovelCaptionFeedItem(novelId))
        if (!novel?.tags.isNullOrEmpty()) items.add(NovelTagsFeedItem(novelId))
        items.add(NovelInfoFeedItem(novelId))
        val user: User? = novel?.user
        user?.let { items.add(NovelAuthorFeedItem(it)) }
        // issue #1005: 懒加载区块头，滚到可见才拉（触发编排见 NovelTextFragment 的 SectionLoader）
        items.add(
            NovelSectionHeaderItem(
                NovelDetailSection.AUTHOR_WORKS,
                userId = user?.id ?: 0L,
                authorName = user?.name.orEmpty(),
            )
        )
        items.add(NovelSectionHeaderItem(NovelDetailSection.RELATED))
        return FeedPage(items, null)
    }

    /**
     * 下拉刷新时无条件换新正文缓存（issue #976）：[NovelTextCache] 无过期机制，作者改文后
     * 进程内命中的永远是旧正文，而 app API 的 novel 对象没有更新时间字段，无从自动判断失效。
     * 先逐出旧条目——即使后面拉取/解析失败，阅读器再进也会走网络拿新的，绝不退回旧缓存。
     */
    private suspend fun refreshNovelText() {
        NovelTextCache.evict(novelId)
        withContext(Dispatchers.Default) {
            // .string() 对 10 万字级正文做 MB 级字符集解码，和解析/分词一起留在 Default 上
            val html = Client.appApi.getNovelText(novelId).string()
            val web = WebNovelParser.parsePixivObject(html)?.novel
                ?: error(Shaft.getContext().getString(R.string.msg_parse_fail))
            NovelTextCache.put(novelId, NovelTextCache.Entry(web, ContentParser.tokenize(web)))
        }
    }
}

// ── Renderers ──

/**
 * bind 阶段挂 LiveData 的辅助：每个 cell 一只勾，重绑先摘旧勾、回收也摘——否则 holder
 * 回收再重绑会往同一条 LiveData 上叠 observer（issue #912 同型：泄漏到 view 销毁，且每次
 * emission 重复跑全部绑定逻辑）。不能图省事 `removeObservers(owner)`：同一篇小说的 LiveData
 * 被本页多张卡（头卡/简介卡/标签卡/信息卡）共享，那会把别的卡也摘下来。
 */
internal class CellObserverSlot<T>(private val lifecycleOwner: LifecycleOwner) {
    private var live: LiveData<T>? = null
    private var observer: Observer<T>? = null

    /** [onChange] 收可空值：ObjectPool 的 LiveData 是 Java 泛型，运行时可能发 null（池里还没有这条）。 */
    fun rebind(newLive: LiveData<T>, onChange: (T?) -> Unit) {
        detach()
        val obs = Observer<T> { onChange(it) }
        live = newLive
        observer = obs
        newLive.observe(lifecycleOwner, obs)
    }

    fun detach() {
        val l = live ?: return
        observer?.let(l::removeObserver)
        live = null
        observer = null
    }
}

/** 模糊背景的强度，与平板作品舞台（ArtworkTabletStage）同一档。 */
private const val HERO_BLUR_RADIUS = 25
private const val HERO_BLUR_SAMPLING = 4

/** 头卡 hero 的状态栏让位：列表顶不留 padding，hero 背景铺进状态栏下面，内容从这条 spacer 之下开始。 */
internal fun CellNovelHeaderBinding.applyStatusBarInset(top: Int) {
    if (statusSpacer.layoutParams.height != top) statusSpacer.updateLayoutParams { height = top }
}

/** 主按钮 / 悬浮胶囊共用的文案：没读过「阅读正文」，读过「继续阅读 · 42%」。 */
internal fun novelReadLabel(context: Context, percent: Int?): String =
    if (percent == null) context.getString(R.string.action_start_reading)
    else context.getString(R.string.novel_continue_reading, percent)

/**
 * 小说详情头卡：hero（模糊封面 + 页面底色渐变、居中封面、标题、作者、元信息、标记、操作行）
 * → 统计条 → 系列行。颜色全部从 [V3Palette] 派生，换主题色 / 日夜即跟随。
 */
fun novelHeaderRenderer(
    lifecycleOwner: LifecycleOwner,
    glide: RequestManager,
    readingPercent: LiveData<Int?>,
    statusBarInset: () -> Int,
): FeedRenderer<NovelHeaderFeedItem, CellNovelHeaderBinding> {
    val novelSlots = HashMap<FeedCell<*, *>, CellObserverSlot<Novel>>()
    val progressSlots = HashMap<FeedCell<*, *>, CellObserverSlot<Int?>>()
    val numbers = NumberFormat.getInstance()
    return feedRenderer(
        inflate = CellNovelHeaderBinding::inflate,
        fullSpan = true,
        create = { cell ->
            val b = cell.binding
            styleNovelHeader(b)
            // 监听只挂一次，点击那一刻经 cell.itemOrNull 取当下条目（绑定零 lambda 分配）
            fun novelId(): Long? = cell.itemOrNull?.novelId
            fun novel(): Novel? = novelId()?.let { ObjectPool.get<Novel>(it).value }
            b.share.setOnClick { v ->
                val id = novelId() ?: return@setOnClick
                v.findActionReceiverOrNull<NovelActionsReceiver>()?.onClickShareNovel(v, id)
            }
            b.cover.setOnClick { v ->
                val id = novelId() ?: return@setOnClick
                v.findActionReceiverOrNull<NovelActionsReceiver>()?.onClickNovelCover(v, id)
            }
            val copyTitle = { Common.copy(b.root.context, novel()?.title) }
            b.title.setOnClick { copyTitle() }
            b.title.setOnLongClickListener { copyTitle(); true }
            b.authorRow.setOnClick { v ->
                val userId = novel()?.user?.id?.takeIf { it > 0 } ?: return@setOnClick
                v.findActionReceiverOrNull<UserActionReceiver>()?.onClickUser(userId)
            }
            b.read.setOnClick { v ->
                val id = novelId() ?: return@setOnClick
                v.findActionReceiverOrNull<NovelActionsReceiver>()?.onClickReadNovel(v, id)
            }
            // sender 传圆钮里的 ProgressImageButton：请求在飞时心原地转圈；长按「按标签收藏」
            b.bookmark.setOnClick {
                val id = novelId() ?: return@setOnClick
                b.bookmarkIcon.findActionReceiverOrNull<NovelActionReceiver>()
                    ?.onClickBookmarkNovel(b.bookmarkIcon, id)
            }
            b.bookmark.setOnLongClickListener { v ->
                val novel = novel() ?: return@setOnLongClickListener false
                openTagBookmarkForNovel(v, novel)
                true
            }
            b.download.setOnClick { v ->
                val id = novelId() ?: return@setOnClick
                v.findActionReceiverOrNull<NovelActionsReceiver>()?.onClickDownloadNovel(v, id)
            }
            b.download.setOnLongClickListener { v ->
                val id = novelId() ?: return@setOnLongClickListener false
                v.findActionReceiverOrNull<NovelActionsReceiver>()?.onLongClickDownloadNovel(v, id)
                true
            }
            b.statBookmarksCell.setOnClick { v ->
                val id = novelId() ?: return@setOnClick
                val ctx = v.context
                ctx.startActivity(
                    Intent(ctx, TemplateActivity::class.java).apply {
                        putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.NOVEL_LIKERS.key)
                        putExtra(Params.NOVEL_ID, id)
                        putExtra(Params.TITLE, novel()?.title)
                    }
                )
            }
            b.statCommentsCell.setOnClick { v ->
                val id = novelId() ?: return@setOnClick
                v.findActionReceiverOrNull<NovelActionsReceiver>()?.onClickNovelComments(v, id)
            }
            b.seriesRow.setOnClick { v ->
                val series = novel()?.series ?: return@setOnClick
                v.findActionReceiverOrNull<NovelSeriesActionReceiver>()?.onClickNovelSeries(v, series)
            }
        },
        recycle = { cell ->
            novelSlots[cell]?.detach()
            progressSlots[cell]?.detach()
            val b = cell.binding
            glide.clear(b.cover)
            glide.clear(b.heroBackdrop)
            glide.clear(b.authorAvatar)
            b.cover.setTag(R.id.novel_header_cover_key, null)
            b.authorAvatar.setTag(R.id.novel_header_avatar_key, null)
        },
        // 从 RV 缓存直接 re-attach 的头卡不会重绑；状态栏高度在它离屏期间变了也要跟上
        attach = { cell -> cell.binding.applyStatusBarInset(statusBarInset()) },
    ) { cell ->
        val b = cell.binding
        b.applyStatusBarInset(statusBarInset())
        novelSlots.getOrPut(cell) { CellObserverSlot(lifecycleOwner) }
            .rebind(ObjectPool.get<Novel>(cell.item.novelId)) { novel ->
                bindNovelHeader(b, novel, glide, numbers)
            }
        progressSlots.getOrPut(cell) { CellObserverSlot(lifecycleOwner) }
            .rebind(readingPercent) { percent -> b.readLabel.text = novelReadLabel(b.root.context, percent) }
    }
}

/** 头卡里不随数据变的部分：主题色派生的底、细线、图标着色。只在 create 跑一次。 */
private fun styleNovelHeader(b: CellNovelHeaderBinding) {
    val ctx = b.root.context
    val p = V3Palette.from(ctx)
    val text2 = ctx.color(R.color.v3_text_2)
    // 涟漪 mask 每个 View 一份：同一个 Drawable 实例只能挂一个 callback，共用会让涟漪画错地方
    fun round() = shape(999f, Color.WHITE)

    // Hero：主题色 alpha20 打底（默认封面 / 还没加载出来时就是它），模糊封面盖在上面，再压页面底色渐变
    b.heroBackdrop.setBackgroundColor(p.alpha20)
    b.heroScrim.background = heroScrim(ctx.color(R.color.v3_bg), p.isDark)

    // 封面：10dp 圆角裁切 + 一圈细描边（浅色封面在浅底上也有边）+ 阴影
    val coverRadius = ctx.dpF(10f)
    b.cover.outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, coverRadius)
        }
    }
    b.cover.clipToOutline = true
    b.cover.foreground = shape(
        coverRadius,
        Color.TRANSPARENT,
        V3Palette.withAlpha(ctx.color(R.color.v3_text_1), if (p.isDark) 0.14f else 0.10f),
        ctx.hairlinePx(),
    )

    // 待操作图标：哑光主题色（浅色）/ 白（深色），不用主题正色也不用黑灰
    b.share.background = ctx.ripple(null, round())
    b.share.imageTintList = ColorStateList.valueOf(p.floatingPillContent)
    b.authorChevron.setImageDrawable(chevron(ctx, text2))
    ViewCompat.setAccessibilityHeading(b.title, true)

    // 主操作：实色主题胶囊（全页唯一最强的元素），字和书本图标用 onPrimary
    b.read.background = ctx.ripple(p.pillPrimary(999f), round())
    b.readLabel.setTextColor(p.onPrimary)
    AppCompatResources.getDrawable(ctx, R.drawable.ic_baseline_menu_book_24)?.mutate()?.let {
        it.setTint(p.onPrimary)
        it.setBounds(0, 0, ctx.dp(20), ctx.dp(20))
        b.readLabel.setCompoundDrawablesRelative(it, null, null, null)
    }
    b.read.pressScale()
    for (circle in listOf(b.bookmark, b.download)) {
        circle.background = ctx.ripple(p.pillSecondary(999f, ctx.hairlinePx()), round())
        circle.pressScale()
    }
    b.downloadIcon.imageTintList = ColorStateList.valueOf(p.floatingPillContent)

    // 统计条 / 系列行的细线；可点的两格给圆角涟漪，标签尾巴带「›」提示能点
    for (line in listOf(b.statsLineTop, b.statsLineBottom, b.statsDivider1, b.statsDivider2, b.seriesLine)) {
        line.setBackgroundColor(p.cardHairline)
    }
    b.statBookmarksCell.background = ctx.ripple(null, shape(ctx.dpF(12f), Color.WHITE))
    b.statCommentsCell.background = ctx.ripple(null, shape(ctx.dpF(12f), Color.WHITE))
    for (label in listOf(b.statBookmarksLabel, b.statCommentsLabel)) {
        chevron(ctx, text2)?.let {
            it.setBounds(0, 0, ctx.dp(12), ctx.dp(12))
            label.setCompoundDrawablesRelative(null, null, it, null)
        }
    }

    // 系列图标容器：40dp、17/17/17/7 的类别图标形状，主题色 alpha20 底
    val big = ctx.dpF(17f)
    val small = ctx.dpF(7f)
    b.seriesIcon.background = GradientDrawable().apply {
        cornerRadii = floatArrayOf(big, big, big, big, big, big, small, small)
        setColor(p.alpha20)
    }
    b.seriesIconImage.imageTintList = ColorStateList.valueOf(p.textSecondary)
    b.seriesChevron.setImageDrawable(chevron(ctx, text2))

    // 标记：中性的用主题色 alpha10 底 + 正文色；R-18 / R-18G 用危险色
    val badgeRadius = ctx.dpF(6f)
    for (badge in listOf(b.badgeOriginal, b.badgeAi)) {
        badge.background = shape(badgeRadius, p.alpha10)
        badge.setTextColor(ctx.color(R.color.v3_text_1))
    }
    val danger = ctx.color(R.color.v3_danger)
    b.badgeRestrict.background = shape(badgeRadius, V3Palette.withAlpha(danger, 0.14f))
    b.badgeRestrict.setTextColor(danger)
}

private fun bindNovelHeader(
    b: CellNovelHeaderBinding,
    novel: Novel?,
    glide: RequestManager,
    numbers: NumberFormat,
) {
    val ctx = b.root.context
    // 标题与系列放在判空之前：novel 为 null 时也要清掉复用 cell 上一篇的内容
    b.title.text = novel?.title
    val seriesTitle = novel?.series?.title
    b.seriesBlock.isVisible = !seriesTitle.isNullOrEmpty()
    b.seriesName.text = seriesTitle
    if (novel == null) return

    loadHeroCover(b, novel, glide)

    val user = novel.user
    b.authorRow.isVisible = user != null
    if (user != null) {
        b.authorName.text = user.name
        val avatar = user.profile_image_urls?.medium
        if (b.authorAvatar.getTag(R.id.novel_header_avatar_key) != avatar) {
            b.authorAvatar.setTag(R.id.novel_header_avatar_key, avatar)
            glide.load(GlideUtil.getHead(user)).error(R.drawable.no_profile).into(b.authorAvatar)
        }
    }

    val meta = buildList {
        novel.text_length?.takeIf { it > 0 }?.let {
            add(ctx.getString(R.string.novel_meta_word_count, numbers.format(it)))
        }
        novelReadingMinutes(novel.text_length)?.let {
            add(ctx.resources.getQuantityString(R.plurals.novel_reading_minutes, it, it))
        }
        novel.create_date?.take(10)?.takeIf { it.isNotEmpty() }?.let(::add)
    }.joinToString(" · ")
    b.meta.text = meta
    b.meta.isVisible = meta.isNotEmpty()

    // AI 标记与屏蔽 / 仅看 AI 同一口径（[IllustNovelFilter.isAiWork]，issue #1252 需求 1）
    b.badgeOriginal.isVisible = novel.is_original == true
    val restrict = when (novel.x_restrict) {
        1 -> ctx.getString(R.string.v3_novel_r18)
        2 -> ctx.getString(R.string.v3_novel_r18g)
        else -> null
    }
    b.badgeRestrict.isVisible = restrict != null
    b.badgeRestrict.text = restrict
    b.badgeAi.isVisible = IllustNovelFilter.isAiWork(novel)
    b.badges.isVisible = b.badgeOriginal.isVisible || b.badgeRestrict.isVisible || b.badgeAi.isVisible

    val bookmarked = novel.is_bookmarked == true
    b.bookmarkIcon.setImageResource(if (bookmarked) R.drawable.icon_liked else R.drawable.icon_not_liked)
    b.bookmarkIcon.imageTintList =
        if (bookmarked) null else ColorStateList.valueOf(V3Palette.from(ctx).floatingPillContent)

    b.statViews.text = numbers.format(novel.total_view ?: 0)
    b.statBookmarks.text = numbers.format(novel.total_bookmarks ?: 0)
    b.statComments.text = numbers.format(novel.total_comments ?: 0)
}

/**
 * 封面优先拿母图（`novelCoverMasterUrl`，240 宽缩略图在 140dp 的封面上是糊的），缩略图当 thumbnail
 * 先出、母图失败时兜底；模糊背景只用缩略图（反正要糊掉）。默认封面不做模糊背景，留主题色打底。
 */
private fun loadHeroCover(b: CellNovelHeaderBinding, novel: Novel, glide: RequestManager) {
    val thumbnail = novel.resolvedCoverUrl()
    if (b.cover.getTag(R.id.novel_header_cover_key) == thumbnail) return
    b.cover.setTag(R.id.novel_header_cover_key, thumbnail)
    val placeholder = V3Palette.from(b.root.context).cardFill.toDrawable()
    val master = novelCoverMasterUrl(thumbnail)
    if (master == null) {
        glide.load(GlideUtil.getUrl(thumbnail)).placeholder(placeholder).error(placeholder).into(b.cover)
        glide.clear(b.heroBackdrop)
        b.heroBackdrop.setImageDrawable(null)
        return
    }
    glide.load(GlideUtil.getUrl(master))
        .thumbnail(glide.load(GlideUtil.getUrl(thumbnail)))
        .error(glide.load(GlideUtil.getUrl(thumbnail)))
        .placeholder(placeholder)
        .into(b.cover)
    glide.load(GlideUtil.getUrl(thumbnail))
        .transform(CpuBlurTransformation(HERO_BLUR_RADIUS, HERO_BLUR_SAMPLING))
        .into(b.heroBackdrop)
}

/**
 * Hero 上的页面底色渐变：顶部半透让模糊封面透出来，底部 100% 融进页面，不留接缝。
 * 深色 50→66→86→100%、浅色 40→60→86→100%，停在 0 / 42% / 80% / 100%；文字仍是 v3_text_1/2，
 * 浅色封面配深色主题也够对比。
 */
private fun heroScrim(@ColorInt pageBg: Int, dark: Boolean): Drawable {
    val alphas = if (dark) floatArrayOf(0.50f, 0.66f, 0.86f, 1f) else floatArrayOf(0.40f, 0.60f, 0.86f, 1f)
    val colors = IntArray(alphas.size) { V3Palette.withAlpha(pageBg, alphas[it]) }
    val stops = floatArrayOf(0f, 0.42f, 0.80f, 1f)
    return PaintDrawable().apply {
        shape = RectShape()
        shaderFactory = object : ShapeDrawable.ShaderFactory() {
            override fun resize(width: Int, height: Int): Shader =
                LinearGradient(0f, 0f, 0f, height.toFloat(), colors, stops, Shader.TileMode.CLAMP)
        }
    }
}

/** 「›」：ic_v3_chevron_24 本身是白色、不跟随 RTL，这里拷一份着色并开自动镜像。 */
internal fun chevron(ctx: Context, @ColorInt tint: Int): Drawable? =
    AppCompatResources.getDrawable(ctx, R.drawable.ic_v3_chevron_24)?.mutate()?.also {
        DrawableCompat.setAutoMirrored(it, true)
        it.setTint(tint)
    }

/**
 * 「信息」区块：取代旧「作品档案」chip 墙，一张两列信息表。折叠态归本 renderer（= 本页面实例），
 * 滚走再滚回不重置；初始值随设置（#1044）。
 */
fun novelInfoRenderer(
    lifecycleOwner: LifecycleOwner,
): FeedRenderer<NovelInfoFeedItem, CellNovelInfoBinding> {
    val slots = HashMap<FeedCell<*, *>, CellObserverSlot<Novel>>()
    var expanded = !Shaft.sSettings.isDetailPanelCollapsedByDefault
    return feedRenderer(
        inflate = CellNovelInfoBinding::inflate,
        fullSpan = true,
        create = { cell ->
            val b = cell.binding
            val ctx = b.root.context
            styleNovelInfoCell(b)
            b.infoHeader.setOnClickListener {
                expanded = !expanded
                applyDetailPanelExpanded(b.infoRows, b.infoArrow, expanded, animate = true)
            }
            fun novel(): Novel? = cell.itemOrNull?.let { ObjectPool.get<Novel>(it.novelId).value }
            b.rowPublished.setOnClick { Common.copy(ctx, novel()?.create_date ?: return@setOnClick) }
            b.rowNovelId.setOnClick { Common.copy(ctx, novel()?.id?.toString() ?: return@setOnClick) }
            b.rowUserId.setOnClick { Common.copy(ctx, novel()?.user?.id?.toString() ?: return@setOnClick) }
            b.novelLinkCopy.setOnClick { Common.copy(ctx, NOVEL_URL_HEAD + (novel()?.id ?: return@setOnClick)) }
            b.novelLinkOpen.setOnClick {
                WebLinkHelper.openInCustomTab(ctx, NOVEL_URL_HEAD + (novel()?.id ?: return@setOnClick))
            }
            b.userLinkCopy.setOnClick {
                Common.copy(ctx, ShareIllust.USER_URL_Head + (novel()?.user?.id ?: return@setOnClick))
            }
            b.userLinkOpen.setOnClick {
                WebLinkHelper.openInCustomTab(ctx, ShareIllust.USER_URL_Head + (novel()?.user?.id ?: return@setOnClick))
            }
        },
        recycle = { cell -> slots[cell]?.detach() },
    ) { cell ->
        val b = cell.binding
        applyDetailPanelExpanded(b.infoRows, b.infoArrow, expanded, animate = false)
        slots.getOrPut(cell) { CellObserverSlot(lifecycleOwner) }
            .rebind(ObjectPool.get<Novel>(cell.item.novelId)) { live ->
                val novel: Novel = live ?: return@rebind
                val published = novel.create_date?.replace('T', ' ')?.take(16)
                b.rowPublished.isVisible = !published.isNullOrEmpty()
                b.line1.isVisible = b.rowPublished.isVisible
                b.valuePublished.text = published
                b.valueNovelId.text = novel.id.toString()
                val userId = novel.user?.id?.takeIf { it > 0 }
                b.rowUserId.isVisible = userId != null
                b.line3.isVisible = userId != null
                b.line4.isVisible = userId != null
                b.rowUserLink.isVisible = userId != null
                b.valueUserId.text = userId?.toString()
            }
    }
}

/** 信息表里不随数据变的部分（小说详情与小说系列共用 cell_novel_info）：细线、图标、动作文字着色。 */
internal fun styleNovelInfoCell(b: CellNovelInfoBinding) {
    val ctx = b.root.context
    val p = V3Palette.from(ctx)
    val text2 = ColorStateList.valueOf(ctx.color(R.color.v3_text_2))
    ViewCompat.setAccessibilityHeading(b.infoTitle, true)
    b.infoArrow.imageTintList = text2
    b.copyNovelId.imageTintList = text2
    b.copyUserId.imageTintList = text2
    for (line in listOf(b.line1, b.line2, b.line3, b.line4)) line.setBackgroundColor(p.cardHairline)
    for (action in listOf(b.novelLinkCopy, b.novelLinkOpen, b.userLinkCopy, b.userLinkOpen)) {
        action.setTextColor(p.textAccent)
    }
}

/**
 * 「作者」区块：头像 + 名字 + @账号 + 关注胶囊（与用户卡同一套 [renderFollowPill]）。
 * 关注态读 ObjectPool（关注 / 取关写穿池子）；「已关注 ↔ 悄悄关注中」只改可见性、池里的
 * is_followed 不变，所以另挂 [FollowVisibility.changes] 重画文案。
 * 点按关注 / 取关；长按：已关注 = 切换公开 / 悄悄（同画师主页），未关注 = 悄悄关注（同用户卡）。
 */
fun novelAuthorRenderer(
    lifecycleOwner: LifecycleOwner,
    glide: RequestManager,
    palette: V3Palette,
): FeedRenderer<NovelAuthorFeedItem, CellNovelAuthorBinding> {
    val userSlots = HashMap<FeedCell<*, *>, CellObserverSlot<User>>()
    val visibilitySlots = HashMap<FeedCell<*, *>, CellObserverSlot<Long>>()
    fun currentUser(snapshot: User): User = ObjectPool.get<User>(snapshot.id).value ?: snapshot
    return feedRenderer(
        inflate = CellNovelAuthorBinding::inflate,
        fullSpan = true,
        create = { cell ->
            val b = cell.binding
            ViewCompat.setAccessibilityHeading(b.authorTitle, true)
            b.authorAvatar.borderColor = palette.primary
            b.follow.pressScale()
            b.authorRow.setOnClick { v ->
                val userId = cell.itemOrNull?.user?.id ?: return@setOnClick
                v.findActionReceiverOrNull<UserActionReceiver>()?.onClickUser(userId)
            }
            // 长按复制作者名：旧页面的「作者」chip / 作者卡长按都能复制，换成信息表后只剩这个入口
            b.authorRow.setOnLongClickListener { v ->
                val name = cell.itemOrNull?.user?.let { currentUser(it).name }
                    ?.takeIf { it.isNotBlank() } ?: return@setOnLongClickListener false
                Common.copy(v.context, name)
                true
            }
            b.follow.setOnClick { v ->
                val user = currentUser(cell.itemOrNull?.user ?: return@setOnClick)
                val target = user.is_followed != true
                playToggleHaptic(v, target)
                // 本地态（池子 + 可见性 + LIKED_USER 广播）当帧落地，请求进 PixivActionQueue；失败由队列回滚
                if (target) {
                    PixivOperate.postFollowUser(user.id, PixivActions.defaultFollowRestrict())
                } else {
                    PixivOperate.postUnFollowUser(user.id)
                }
                b.follow.renderFollowPill(palette, user.id, target)
            }
            b.follow.setOnLongClickListener { v ->
                val user = currentUser(cell.itemOrNull?.user ?: return@setOnLongClickListener false)
                if (user.is_followed == true) {
                    PixivActions.switchFollowVisibility(user.id) // 文案经 FollowVisibility.changes 重画
                } else {
                    playToggleHaptic(v, true)
                    PixivOperate.postFollowUser(user.id, Params.TYPE_PRIVATE)
                    b.follow.renderFollowPill(palette, user.id, true)
                }
                true
            }
        },
        recycle = { cell ->
            userSlots[cell]?.detach()
            visibilitySlots[cell]?.detach()
            glide.clear(cell.binding.authorAvatar)
            cell.binding.authorAvatar.setTag(R.id.novel_header_avatar_key, null)
        },
    ) { cell ->
        val b = cell.binding
        val snapshot = cell.item.user
        bindNovelAuthor(b, currentUser(snapshot), glide, palette)
        userSlots.getOrPut(cell) { CellObserverSlot(lifecycleOwner) }
            .rebind(ObjectPool.get<User>(snapshot.id)) { user ->
                bindNovelAuthor(b, user ?: snapshot, glide, palette)
            }
        visibilitySlots.getOrPut(cell) { CellObserverSlot(lifecycleOwner) }
            .rebind(FollowVisibility.changes) { changedId ->
                if (changedId != snapshot.id || !b.follow.isVisible) return@rebind
                b.follow.renderFollowPill(palette, snapshot.id, currentUser(snapshot).is_followed == true)
            }
    }
}

private fun bindNovelAuthor(b: CellNovelAuthorBinding, user: User, glide: RequestManager, palette: V3Palette) {
    b.authorName.text = user.name
    val account = user.account
    b.authorAccount.isVisible = !account.isNullOrBlank()
    if (b.authorAccount.isVisible) b.authorAccount.text = "@$account"
    val avatar = user.profile_image_urls?.medium
    if (b.authorAvatar.getTag(R.id.novel_header_avatar_key) != avatar) {
        b.authorAvatar.setTag(R.id.novel_header_avatar_key, avatar)
        glide.load(GlideUtil.getHead(user)).error(R.drawable.no_profile).into(b.authorAvatar)
    }
    // 自己的作品不给关注按钮（关注自己会被服务端拒掉，队列几分钟后再回滚，按钮来回跳）
    val isSelf = user.id == SessionManager.loggedInUid
    b.follow.isVisible = !isSelf
    if (!isSelf) b.follow.renderFollowPill(palette, user.id, user.is_followed == true)
}

fun novelTagsRenderer(
    lifecycleOwner: LifecycleOwner,
): FeedRenderer<NovelTagsFeedItem, CellNovelTagsBinding> {
    val slots = HashMap<FeedCell<*, *>, CellObserverSlot<Novel>>()
    return feedRenderer(
        inflate = CellNovelTagsBinding::inflate,
        fullSpan = true,
        create = { cell ->
            ViewCompat.setAccessibilityHeading(cell.binding.tagsLabel, true)
            cell.binding.tagsFlow.searchIndex = 1 // novels tab in SearchActivity
            // 长按菜单补「固定/取消固定」，对齐插画详情（#1038）。与插画唯一的差异是
            // 不构造 previewIllustsJson——那是插画强类型的封面预览，置顶标签页对没有
            // 预览的记录会回退成关键词卡，语义正确。
            cell.binding.tagsFlow.onPinTag = { name, _, newPinned ->
                PixivOperate.insertPinnedSearchHistory(
                    name, SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD, newPinned,
                )
                Common.showToast(R.string.operate_success)
            }
        },
        recycle = { cell -> slots[cell]?.detach() },
    ) { cell ->
        val b = cell.binding
        slots.getOrPut(cell) { CellObserverSlot(lifecycleOwner) }
            .rebind(ObjectPool.get<Novel>(cell.item.novelId)) { live ->
                val novel: Novel = live ?: return@rebind
                b.tagsFlow.onViewAuthorWorks = novel.user?.id?.takeIf { it > 0L }?.let { userId ->
                    { name ->
                        b.root.context.startActivity(Intent(b.root.context, TemplateActivity::class.java).apply {
                            putExtra(Params.USER_ID, userId)
                            putExtra(Params.KEY_WORD, name)
                            putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.USER_NOVELS_BY_TAG.key)
                        })
                    }
                }
                b.tagsFlow.setTags(novel.tags.orEmpty())
            }
    }
}

/**
 * 简介折叠态（issue #1005，对齐插画详情 #965）：归 Fragment 持有，
 * 滚走再滚回 / toggle 后重绑都不会丢展开状态。
 */
class NovelCaptionCollapse {
    var expanded: Boolean = false
    /** 全文富文本（折叠态视图上只有截断文本，展开要拿它回填）。 */
    var full: CharSequence? = null
}

/** 简介折叠阈值：与插画详情 DESC_COLLAPSED_LINES 同值（#965 定的 5 行）。 */
private const val CAPTION_COLLAPSED_LINES = 5

/**
 * 超长简介折叠。折叠不能只靠 maxLines：无 ellipsize 时 layout 仍排全文，textIsSelectable
 * 的点按（bringPointIntoView）和 LinkMovementMethod 的拖动会把隐藏行滚出来。所以折叠态在
 * 文本层面截到第 [CAPTION_COLLAPSED_LINES] 行止；全文行数要等 layout 出来才知道，截断和
 * toggle 可见性都放 doOnPreDraw 里做，注册前先取消同一 TextView 上未执行的旧回调并在执行时
 * 复核身份（实现口径逐条对齐插画详情的 applyDescCollapseState，理由详见彼处 KDoc）。
 */
private fun applyNovelCaptionCollapse(b: CellNovelCaptionBinding, collapse: NovelCaptionCollapse) {
    val full = collapse.full ?: return
    val expectedCaption = b.caption.getTag(R.id.novel_caption_rendered)
    b.caption.maxLines = if (collapse.expanded) Int.MAX_VALUE else CAPTION_COLLAPSED_LINES
    b.caption.text = full
    b.caption.scrollTo(0, 0)
    b.captionToggle.setText(
        if (collapse.expanded) R.string.v3_desc_collapse else R.string.v3_desc_expand
    )
    clearPendingCaptionPreDraw(b)
    lateinit var request: OneShotPreDrawListener
    request = b.caption.doOnPreDraw {
        // 新一次 bind/toggle 已取代本次请求时绝不再碰当前 holder
        if (b.caption.getTag(R.id.v3_desc_predraw_listener) !== request) {
            return@doOnPreDraw
        }
        b.caption.setTag(R.id.v3_desc_predraw_listener, null)
        if (b.caption.getTag(R.id.novel_caption_rendered) != expectedCaption ||
            collapse.full !== full
        ) {
            return@doOnPreDraw
        }
        val layout = b.caption.layout ?: return@doOnPreDraw
        val overflow = layout.lineCount > CAPTION_COLLAPSED_LINES
        b.captionToggle.isVisible = overflow
        if (!collapse.expanded && overflow) {
            // 即使平台 Layout 给出异常 offset，也不能让展示逻辑把详情页带崩
            val end = layout.getLineEnd(CAPTION_COLLAPSED_LINES - 1).coerceIn(0, full.length)
            b.caption.text = full.subSequence(0, end).trimEnd()
        }
    }
    b.caption.setTag(R.id.v3_desc_predraw_listener, request)
}

private fun clearPendingCaptionPreDraw(b: CellNovelCaptionBinding) {
    (b.caption.getTag(R.id.v3_desc_predraw_listener) as? OneShotPreDrawListener)?.removeListener()
    b.caption.setTag(R.id.v3_desc_predraw_listener, null)
}

fun novelCaptionRenderer(
    lifecycleOwner: LifecycleOwner,
    collapse: NovelCaptionCollapse,
    /** 收起后把简介块拉回视口（长简介收起时锚点会跳到很下面的区块，见插画侧同名逻辑）。 */
    onCollapsed: (View) -> Unit,
): FeedRenderer<NovelCaptionFeedItem, CellNovelCaptionBinding> {
    val slots = HashMap<FeedCell<*, *>, CellObserverSlot<Novel>>()
    return feedRenderer(
        inflate = CellNovelCaptionBinding::inflate,
        fullSpan = true,
        create = { cell -> setupNovelCaptionCell(cell.binding, collapse, onCollapsed) },
        recycle = { cell ->
            slots[cell]?.detach()
            recycleNovelCaptionCell(cell.binding)
        },
    ) { cell ->
        val b = cell.binding
        val liveNovel = ObjectPool.get<Novel>(cell.item.novelId)
        // 本 observer 挂在 ObjectPool 上，这条小说**任何**字段的更新（收藏切换最常见）都会
        // 让它重新发射；renderNovelCaption 对同一段原文直接跳过。
        slots.getOrPut(cell) { CellObserverSlot(lifecycleOwner) }.rebind(liveNovel) { novel ->
            renderNovelCaption(b, novel?.caption.orEmpty(), collapse)
        }
    }
}

/**
 * 简介格只做一次的配置（小说详情与小说系列共用 cell_novel_caption）：链接与「展开 / 收起」用
 * textAccent，区块标题标成无障碍标题。
 */
internal fun setupNovelCaptionCell(
    b: CellNovelCaptionBinding,
    collapse: NovelCaptionCollapse,
    onCollapsed: (View) -> Unit,
) {
    val accent = V3Palette.from(b.root.context).textAccent
    ViewCompat.setAccessibilityHeading(b.captionLabel, true)
    b.caption.setLinkTextColor(accent)
    b.captionToggle.setTextColor(accent)
    b.captionToggle.setOnClick {
        collapse.expanded = !collapse.expanded
        applyNovelCaptionCollapse(b, collapse)
        if (!collapse.expanded) onCollapsed(b.root)
    }
}

/** 回收时调：复用到另一条时不能沿用上一条的「已渲染 caption」短路标记和未执行的折叠测量。 */
internal fun recycleNovelCaptionCell(b: CellNovelCaptionBinding) {
    b.caption.setTag(R.id.novel_caption_rendered, null)
    clearPendingCaptionPreDraw(b)
}

/**
 * 把 pixiv 简介（带 `<br />` / `<a>` 的 HTML）渲进简介格：站内作品 / 用户链接走 receiver，
 * 其它 http(s) 链接外开；超长折叠（#1005，对齐插画详情 #965）；点正文复制纯文本。
 * fromHtml + CustomLinkMovementMethod 重建都不便宜，原文没变就整段跳过 —— 视图上现有的
 * 富文本与监听仍然是对的。
 */
internal fun renderNovelCaption(b: CellNovelCaptionBinding, rawCaption: String, collapse: NovelCaptionCollapse) {
    if (b.caption.getTag(R.id.novel_caption_rendered) == rawCaption) return
    b.caption.setTag(R.id.novel_caption_rendered, rawCaption)
    val ctx = b.root.context
    val hasCaption = rawCaption.isNotEmpty()
    val normalizedCaption = rawCaption.replace("\r\n", "\n").replace("\n", "<br/>")
    if (hasCaption) {
        b.caption.isVisible = true
        val linkHandler = CustomLinkMovementMethod { link ->
            val info = extractPixivId(link)
            when (info.type) {
                "novels" -> info.value.toLongOrNull()?.let { id ->
                    b.caption.findActionReceiverOrNull<NovelActionReceiver>()?.visitNovelById(id)
                }
                "illusts" -> info.value.toLongOrNull()?.let { id ->
                    b.caption.findActionReceiverOrNull<IllustCardActionReceiver>()?.visitIllustById(id)
                }
                "users" -> info.value.toLongOrNull()?.let { id ->
                    b.caption.findActionReceiverOrNull<UserActionReceiver>()?.onClickUser(id)
                }
                else -> {
                    val uri = runCatching { link.toUri() }.getOrNull()
                    val scheme = uri?.scheme?.lowercase()
                    if (uri != null && (scheme == "http" || scheme == "https")) {
                        WebLinkHelper.openUrl(b.caption.context, uri)
                    }
                }
            }
            Timber.d("caption link clicked: $info")
        }
        b.caption.movementMethod = linkHandler
        // 全文存进 collapse 供展开回填
        collapse.full = HtmlCompat.fromHtml(normalizedCaption, HtmlCompat.FROM_HTML_MODE_COMPACT)
        applyNovelCaptionCollapse(b, collapse)
        b.caption.setOnClick {
            if (linkHandler.wasLinkClicked) return@setOnClick
            val plain = HtmlCompat.fromHtml(normalizedCaption, HtmlCompat.FROM_HTML_MODE_COMPACT)
                .toString().trim()
            Common.copy(ctx, plain)
        }
    } else {
        b.caption.isVisible = false
        collapse.full = null
        b.captionToggle.isVisible = false
    }
}

/**
 * 懒加载区块头（作者往期作品 / 相关小说，issue #1005）。小说页自己的布局
 * （cell_novel_section_header），不再借插画详情的 section_v3_related_header：
 * 作者往期是「作者」区块里的子行（16sp SemiBold +「查看更多」），相关作品是独立区块（20sp Bold）。
 * 同一个 ViewHolder 可能在两种区块之间复用，所以字号字重每次绑定都按区块类型重设。
 * 触发懒加载走两条路（SectionLoader 幂等去重）：attach 管「滚到才见」，
 * bind 兜「下拉刷新后区块头原地重绑、不再走 attach」的那批。
 */
fun novelSectionHeaderRenderer(): FeedRenderer<NovelSectionHeaderItem, CellNovelSectionHeaderBinding> =
    feedRenderer(
        inflate = CellNovelSectionHeaderBinding::inflate,
        fullSpan = true,
        create = { cell ->
            val b = cell.binding
            val p = V3Palette.from(b.root.context)
            ViewCompat.setAccessibilityHeading(b.sectionTitle, true)
            b.sectionSeeMore.setTextColor(p.textAccent)
            b.sectionSpinner.indeterminateTintList = ColorStateList.valueOf(p.textAccent)
            b.sectionSeeMore.setOnClick { sender ->
                val item = cell.itemOrNull ?: return@setOnClick
                if (item.section == NovelDetailSection.AUTHOR_WORKS && item.userId > 0) {
                    val ctx = sender.context
                    ctx.startActivity(Intent(ctx, TemplateActivity::class.java).apply {
                        putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.USER_NOVELS.key)
                        putExtra(Params.USER_ID, item.userId)
                    })
                }
            }
        },
        attach = { cell ->
            val item = cell.itemOrNull
            if (item != null && item.state == null) {
                cell.binding.root.findActionReceiverOrNull<NovelSectionReceiver>()
                    ?.onNovelSectionVisible(item.section)
            }
        },
    ) { cell ->
        val b = cell.binding
        val ctx = b.root.context
        val item = cell.item
        when (item.section) {
            NovelDetailSection.AUTHOR_WORKS -> {
                b.sectionTitle.setText(R.string.novel_author_other_works)
                b.sectionTitle.textSize = 16f
                b.sectionTitle.typeface = ctx.v3Font(600)
                b.root.setPaddingRelative(b.root.paddingStart, 0, b.root.paddingEnd, 0)
            }
            NovelDetailSection.RELATED -> {
                b.sectionTitle.setText(R.string.v3_label_related)
                b.sectionTitle.textSize = 20f
                b.sectionTitle.typeface = ctx.v3Font(700)
                // 区块上留白 24dp，其中 ~12dp 由 48dp 标题行自身的上半截补齐
                b.root.setPaddingRelative(b.root.paddingStart, ctx.dp(12), b.root.paddingEnd, 0)
            }
        }
        // 相关小说没有独立整页（web 推荐源无翻页语义），「查看更多」只给作者往期
        b.sectionSeeMore.isVisible = item.section == NovelDetailSection.AUTHOR_WORKS && item.userId > 0
        b.sectionLoading.isVisible = item.state == null
        b.sectionEmpty.isVisible = item.state == false
        if (item.state == null) {
            b.root.findActionReceiverOrNull<NovelSectionReceiver>()
                ?.onNovelSectionVisible(item.section)
        }
    }
