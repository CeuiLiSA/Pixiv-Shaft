package ceui.pixiv.ui.novel

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import androidx.core.text.HtmlCompat
import androidx.core.view.OneShotPreDrawListener
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.fragments.WebNovelParser
import ceui.pixiv.ui.bookmark.SelectTagBottomSheet
import ceui.lisa.databinding.CellNovelActionsBinding
import ceui.lisa.databinding.CellNovelCaptionBinding
import ceui.lisa.databinding.CellNovelHeaderBinding
import ceui.lisa.databinding.CellNovelProfileBinding
import ceui.lisa.databinding.CellNovelTagsBinding
import ceui.lisa.databinding.SectionV3RelatedHeaderBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.lisa.utils.ShareIllust
import ceui.lisa.utils.V3Palette
import ceui.loxia.Client
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.loxia.Series
import ceui.loxia.User
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.feeds.FeedCell
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.FeedViewModel
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.ui.common.IllustCardActionReceiver
import ceui.pixiv.ui.common.NOVEL_URL_HEAD
import ceui.pixiv.ui.common.bindCopyChip
import ceui.pixiv.ui.common.bindCopyLinkChip
import ceui.pixiv.ui.common.bindOpenLinkChip
import ceui.pixiv.ui.common.NovelActionReceiver
import ceui.pixiv.ui.common.NovelFeedItem
import ceui.pixiv.ui.detail.SeriesAuthorFeedItem
import ceui.pixiv.ui.novel.reader.NovelTextCache
import ceui.pixiv.ui.novel.reader.paginate.ContentParser
import ceui.pixiv.ui.user.UserActionReceiver
import ceui.pixiv.utils.extractPixivId
import ceui.pixiv.utils.setOnClick
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.text.NumberFormat

// ── 从旧 holder 文件迁来的公共契约（同包，FQN 不变，外部 import 无需改）──────

interface NovelSeriesActionReceiver {
    fun onClickNovelSeries(sender: View, series: Series)
}

interface NovelActionsReceiver {
    fun onClickShareNovel(sender: View, novelId: Long)
    fun onClickNovelComments(sender: View, novelId: Long)
    fun onClickDownloadNovel(sender: View, novelId: Long)
    fun onLongClickDownloadNovel(sender: View, novelId: Long)
}

internal fun openTagBookmarkForNovel(sender: View, novel: Novel) {
    // 只有 View / Context，用 showFrom 解出宿主 FragmentActivity 弹 sheet
    // （原先是 startActivity 一张从右侧 push 进来的整页）。
    val tagNames = novel.tags.orEmpty().mapNotNull { it.name }.toTypedArray()
    SelectTagBottomSheet.showFrom(sender.context, novel.id.toInt(), Params.TYPE_NOVEL, tagNames)
}

// ── FeedItem 模型（都以 novelId 为身份；实际小说数据由渲染器观察 ObjectPool 取）─────

data class NovelHeaderFeedItem(val novelId: Long) : FeedItem {
    override val feedKey: Any get() = novelId
}

data class NovelProfileFeedItem(val novelId: Long) : FeedItem {
    override val feedKey: Any get() = novelId
}

data class NovelActionsFeedItem(val novelId: Long) : FeedItem {
    override val feedKey: Any get() = novelId
}

data class NovelTagsFeedItem(val novelId: Long) : FeedItem {
    override val feedKey: Any get() = novelId
}

data class NovelCaptionFeedItem(val novelId: Long) : FeedItem {
    override val feedKey: Any get() = novelId
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

/** 区块预览条数：issue #1005 报告人的建议——往期约 3 部、相关约 5 部。 */
private const val AUTHOR_WORKS_PREVIEW_COUNT = 3
private const val RELATED_PREVIEW_COUNT = 5

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
 * [ceui.loxia.PixivWebApi.getNovelRecommendInit] 的 KDoc），detail 并发补水：
 * 单条失败（已删除/不可见）跳过；推荐非空而补水全军覆没则抛出去走 SectionLoader
 * 的自动重试——那是网络挂了，不是「没有相关作品」。
 */
private suspend fun fetchNovelRelated(novelId: Long): List<NovelFeedItem> = coroutineScope {
    val ids = Client.webApi.getNovelRecommendInit(novelId, limit = RELATED_PREVIEW_COUNT + 3)
        .body?.novels.orEmpty()
        .asSequence()
        .filter { it.isMasked != true }
        .mapNotNull { it.id?.toLongOrNull() }
        .filter { it != novelId }
        .take(RELATED_PREVIEW_COUNT + 3)
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
        }
        if (cursor == null && hasLoadedOnce) refreshNovelText()
        hasLoadedOnce = true
        val items = mutableListOf<FeedItem>()
        items.add(NovelHeaderFeedItem(novelId))
        val user: User? = novel?.user ?: ObjectPool.get<Novel>(novelId).value?.user
        user?.let { items.add(SeriesAuthorFeedItem(it)) }
        items.add(NovelProfileFeedItem(novelId))
        items.add(NovelActionsFeedItem(novelId))
        items.add(NovelTagsFeedItem(novelId))
        items.add(NovelCaptionFeedItem(novelId))
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

// ── Renderers（复用旧 cell XML，bind 逻辑对齐旧 ViewHolder）──

/**
 * bind 阶段挂 LiveData 的辅助：每个 cell 一只勾，重绑先摘旧勾、回收也摘——否则 holder
 * 回收再重绑会往同一条 LiveData 上叠 observer（issue #912 同型：泄漏到 view 销毁，且每次
 * emission 重复跑全部绑定逻辑）。不能图省事 `removeObservers(owner)`：同一篇小说的 LiveData
 * 被本页多张卡（头卡/统计卡/标签卡/简介卡）共享，那会把别的卡也摘下来。
 */
private class CellObserverSlot<T>(private val lifecycleOwner: LifecycleOwner) {
    private var live: LiveData<T>? = null
    private var observer: Observer<T>? = null

    fun rebind(newLive: LiveData<T>, onChange: (T) -> Unit) {
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

fun novelHeaderRenderer(
    lifecycleOwner: LifecycleOwner,
): FeedRenderer<NovelHeaderFeedItem, CellNovelHeaderBinding> {
    val slots = HashMap<FeedCell<*, *>, CellObserverSlot<Novel>>()
    return feedRenderer(
        inflate = CellNovelHeaderBinding::inflate,
        fullSpan = true,
        create = { cell ->
            val b = cell.binding
            b.lifecycleOwner = lifecycleOwner
            val ctx = b.root.context
            val d = ctx.resources.displayMetrics.density
            val palette = V3Palette.from(ctx)
            b.seriesStrip.background = palette.seriesStripBg(20f * d)
            b.seriesIcon.background = palette.seriesIconBg(10f * d)
            b.seriesName.setTextColor(palette.seriesStripText)
            b.seriesLabel.setTextColor(palette.seriesStripText)
            b.seriesChevron.setTextColor(palette.seriesStripText)
            // 监听只挂一次，点击那一刻经 cell.item 取当下条目（绑定零 lambda 分配）
            // issue #1005: 右上角与插画详情对齐改放「分享」，收藏心挪进下方三大按钮
            b.share.setOnClick { sender ->
                val novelId = cell.itemOrNull?.novelId ?: return@setOnClick
                sender.findActionReceiverOrNull<NovelActionsReceiver>()
                    ?.onClickShareNovel(sender, novelId)
            }
            b.seriesStrip.setOnClick { sender ->
                val series = cell.itemOrNull
                    ?.let { ObjectPool.get<Novel>(it.novelId).value }?.series ?: return@setOnClick
                sender.findActionReceiverOrNull<NovelSeriesActionReceiver>()
                    ?.onClickNovelSeries(sender, series)
            }
            val copyTitle = {
                val title = cell.itemOrNull?.let { ObjectPool.get<Novel>(it.novelId).value }?.title
                Common.copy(ctx, title)
            }
            b.title.setOnClick { copyTitle() }
            b.title.setOnLongClickListener { copyTitle(); true }
        },
        recycle = { cell -> slots[cell]?.detach() },
    ) { cell ->
        val b = cell.binding
        val ctx = b.root.context
        val liveNovel = ObjectPool.get<Novel>(cell.item.novelId)
        b.novel = liveNovel
        slots.getOrPut(cell) { CellObserverSlot(lifecycleOwner) }.rebind(liveNovel) { novel ->
            if (novel == null) return@rebind
            b.metaDate.text = novel.create_date?.replace('T', ' ')?.take(16).orEmpty()
            val isAi = novel.novel_ai_type == 2
            b.metaAi.isVisible = isAi
            b.metaDotAi.isVisible = isAi
            val wordCount = novel.text_length
            if (wordCount != null && wordCount > 0) {
                b.metaWordCount.text = ctx.getString(
                    R.string.novel_meta_word_count,
                    NumberFormat.getInstance().format(wordCount),
                )
                b.metaWordCount.isVisible = true
                b.metaDot2.isVisible = true
            } else {
                b.metaWordCount.isVisible = false
                b.metaDot2.isVisible = false
            }
        }
    }
}

fun novelProfileRenderer(
    lifecycleOwner: LifecycleOwner,
): FeedRenderer<NovelProfileFeedItem, CellNovelProfileBinding> {
    val slots = HashMap<FeedCell<*, *>, CellObserverSlot<Novel>>()
    return feedRenderer(
        inflate = CellNovelProfileBinding::inflate,
        fullSpan = true,
        create = { cell ->
            val wrap = cell.binding.statBookmarkWrap
            applyTouchScale(wrap)
            wrap.setOnClickListener {
                val novelId = cell.item.novelId
                val ctx = wrap.context
                val title = ObjectPool.get<Novel>(novelId).value?.title
                ctx.startActivity(
                    Intent(ctx, TemplateActivity::class.java).apply {
                        putExtra(TemplateActivity.EXTRA_FRAGMENT, "喜欢这部小说的用户")
                        putExtra(Params.NOVEL_ID, novelId)
                        putExtra(Params.TITLE, title)
                    }
                )
            }
        },
        recycle = { cell -> slots[cell]?.detach() },
    ) { cell ->
        val b = cell.binding
        val fmt = NumberFormat.getInstance()

        slots.getOrPut(cell) { CellObserverSlot(lifecycleOwner) }
            .rebind(ObjectPool.get<Novel>(cell.item.novelId)) { novel ->
            if (novel == null) return@rebind
            b.statViews.text = fmt.format(novel.total_view ?: 0)
            b.statBookmarks.text = fmt.format(novel.total_bookmarks ?: 0)

            b.chipNovelId.bindCopyChip(R.string.novel_chip_id, novel.id.toString(), novel.id.toString())
            novel.text_length?.let {
                b.chipTextLength.bindCopyChip(R.string.novel_chip_text_length, it.toString(), it.toString())
            } ?: run { b.chipTextLength.isVisible = false }
            novel.total_view?.let {
                b.chipTotalView.bindCopyChip(R.string.novel_chip_total_view, it.toString(), it.toString())
            } ?: run { b.chipTotalView.isVisible = false }
            novel.total_bookmarks?.let {
                b.chipTotalBookmarks.bindCopyChip(R.string.novel_chip_total_bookmarks, it.toString(), it.toString())
            } ?: run { b.chipTotalBookmarks.isVisible = false }
            novel.create_date?.let {
                val display = it.replace('T', ' ').take(16)
                b.chipCreateDate.bindCopyChip(R.string.novel_chip_create_date, display, it)
            } ?: run { b.chipCreateDate.isVisible = false }
            novel.user?.let { user ->
                val name = user.name.orEmpty()
                b.chipAuthor.bindCopyChip(R.string.novel_chip_author, name, name)
                b.chipAuthorId.bindCopyChip(R.string.novel_chip_author_id, user.id.toString(), user.id.toString())
                b.chipUserLink.bindOpenLinkChip(R.string.novel_chip_user_link, ShareIllust.USER_URL_Head + user.id)
            } ?: run {
                b.chipAuthor.isVisible = false
                b.chipAuthorId.isVisible = false
                b.chipUserLink.isVisible = false
            }
            val novelUrl = NOVEL_URL_HEAD + novel.id
            b.chipNovelLink.bindCopyLinkChip(R.string.novel_chip_novel_link, novelUrl)
            b.chipOpenNovelLink.bindOpenLinkChip(R.string.novel_chip_open_novel_link, novelUrl)
        }
    }
}

private fun applyTouchScale(view: View, scale: Float = 0.97f) {
    view.setOnTouchListener { v, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN ->
                v.animate().scaleX(scale).scaleY(scale).setDuration(200).start()

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        }
        false
    }
}

fun novelActionsRenderer(
    lifecycleOwner: LifecycleOwner,
): FeedRenderer<NovelActionsFeedItem, CellNovelActionsBinding> {
    val slots = HashMap<FeedCell<*, *>, CellObserverSlot<Novel>>()
    return feedRenderer(
        inflate = CellNovelActionsBinding::inflate,
        fullSpan = true,
        create = { cell ->
            val b = cell.binding
            // issue #1005: 收藏顶了原「分享」的位置（分享去了头卡右上角）。sender 传胶囊里的
            // ProgressImageButton——请求在飞时心原地转圈（对齐旧头卡红心的反馈）；
            // 长按沿用红心的「按标签收藏」。
            b.btnBookmark.setOnClick {
                val novelId = cell.itemOrNull?.novelId ?: return@setOnClick
                b.bookmarkIcon.findActionReceiverOrNull<NovelActionReceiver>()
                    ?.onClickBookmarkNovel(b.bookmarkIcon, novelId)
            }
            b.btnBookmark.setOnLongClickListener { sender ->
                val novel = cell.itemOrNull?.let { ObjectPool.get<Novel>(it.novelId).value }
                    ?: return@setOnLongClickListener false
                openTagBookmarkForNovel(sender, novel)
                true
            }
            b.btnComments.setOnClick {
                val novelId = cell.itemOrNull?.novelId ?: return@setOnClick
                it.findActionReceiverOrNull<NovelActionsReceiver>()?.onClickNovelComments(it, novelId)
            }
            b.btnDownload.setOnClick {
                val novelId = cell.itemOrNull?.novelId ?: return@setOnClick
                it.findActionReceiverOrNull<NovelActionsReceiver>()?.onClickDownloadNovel(it, novelId)
            }
            b.btnDownload.setOnLongClickListener { sender ->
                val novelId = cell.itemOrNull?.novelId ?: return@setOnLongClickListener false
                sender.findActionReceiverOrNull<NovelActionsReceiver>()?.onLongClickDownloadNovel(sender, novelId)
                true
            }
        },
        recycle = { cell -> slots[cell]?.detach() },
    ) { cell ->
        val b = cell.binding
        val ctx = b.root.context
        slots.getOrPut(cell) { CellObserverSlot(lifecycleOwner) }
            .rebind(ObjectPool.get<Novel>(cell.item.novelId)) { novel ->
                if (novel == null) return@rebind
                val bookmarked = novel.is_bookmarked == true
                b.bookmarkIcon.setImageResource(
                    if (bookmarked) R.drawable.icon_liked else R.drawable.icon_not_liked
                )
                b.bookmarkIcon.imageTintList = if (bookmarked) null
                    else ColorStateList.valueOf(ctx.getColor(R.color.v3_text_1))
            }
    }
}

fun novelTagsRenderer(
    lifecycleOwner: LifecycleOwner,
): FeedRenderer<NovelTagsFeedItem, CellNovelTagsBinding> {
    val slots = HashMap<FeedCell<*, *>, CellObserverSlot<Novel>>()
    return feedRenderer(
        inflate = CellNovelTagsBinding::inflate,
        fullSpan = true,
        create = { cell -> cell.binding.tagsFlow.searchIndex = 1 }, // novels tab in SearchActivity
        recycle = { cell -> slots[cell]?.detach() },
    ) { cell ->
        val b = cell.binding
        slots.getOrPut(cell) { CellObserverSlot(lifecycleOwner) }
            .rebind(ObjectPool.get<Novel>(cell.item.novelId)) { novel ->
                b.tagsFlow.setTags(novel?.tags.orEmpty())
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
        create = { cell ->
            cell.binding.lifecycleOwner = lifecycleOwner
            cell.binding.captionToggle.setOnClick {
                collapse.expanded = !collapse.expanded
                applyNovelCaptionCollapse(cell.binding, collapse)
                if (!collapse.expanded) onCollapsed(cell.binding.root)
            }
        },
        recycle = { cell ->
            slots[cell]?.detach()
            // 复用到另一条小说时不能沿用上一条的「已渲染 caption」短路标记
            cell.binding.caption.setTag(R.id.novel_caption_rendered, null)
            clearPendingCaptionPreDraw(cell.binding)
        },
    ) { cell ->
        val b = cell.binding
        val ctx = b.root.context
        val liveNovel = ObjectPool.get<Novel>(cell.item.novelId)
        b.novel = liveNovel
        slots.getOrPut(cell) { CellObserverSlot(lifecycleOwner) }.rebind(liveNovel) { novel ->
            val rawCaption = novel.caption.orEmpty()
            // 本 observer 挂在 ObjectPool 上，这条小说**任何**字段的更新（收藏切换最常见）都会
            // 让它重新发射，而下面的 fromHtml 解析 + CustomLinkMovementMethod 重建都不便宜。
            // caption 原文没变就整段跳过——视图上现有的富文本与监听仍然是对的。
            if (b.caption.getTag(R.id.novel_caption_rendered) == rawCaption) return@rebind
            b.caption.setTag(R.id.novel_caption_rendered, rawCaption)
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
                            val uri = runCatching { Uri.parse(link) }.getOrNull()
                            val scheme = uri?.scheme?.lowercase()
                            if (uri != null && (scheme == "http" || scheme == "https")) {
                                runCatching {
                                    b.caption.context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                }
                            }
                        }
                    }
                    Timber.d("caption link clicked: $info")
                }
                b.caption.movementMethod = linkHandler
                // issue #1005: 超长简介折叠（对齐插画详情 #965），全文存进 collapse 供展开回填
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
    }
}

/**
 * 区块头（作者往期作品 / 相关小说，issue #1005）。复用插画详情的 section_v3_related_header
 * 布局（标签 + 「查看更多」 + 转圈 + 空态）。触发懒加载走两条路（SectionLoader 幂等去重）：
 * attach 管「滚到才见」，bind 兜「下拉刷新后区块头原地重绑、不再走 attach」的那批。
 */
fun novelSectionHeaderRenderer(): FeedRenderer<NovelSectionHeaderItem, SectionV3RelatedHeaderBinding> =
    feedRenderer(
        inflate = SectionV3RelatedHeaderBinding::inflate,
        fullSpan = true,
        create = { cell ->
            cell.binding.relatedSeeMore.setOnClick { sender ->
                val item = cell.itemOrNull ?: return@setOnClick
                if (item.section == NovelDetailSection.AUTHOR_WORKS && item.userId > 0) {
                    val ctx = sender.context
                    ctx.startActivity(Intent(ctx, TemplateActivity::class.java).apply {
                        putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说作品")
                        putExtra(Params.USER_ID, item.userId.toInt())
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
        val item = cell.item
        b.relatedLabel.text = when (item.section) {
            NovelDetailSection.AUTHOR_WORKS ->
                b.root.context.getString(R.string.v3_author_works, item.authorName)
            NovelDetailSection.RELATED -> b.root.context.getString(R.string.v3_label_related)
        }
        // 相关小说没有独立整页（web 推荐源无翻页语义），「查看更多」只给作者往期
        b.relatedSeeMore.isVisible = item.section == NovelDetailSection.AUTHOR_WORKS && item.userId > 0
        b.relatedLoadingContainer.isVisible = item.state == null
        b.relatedEmpty.isVisible = item.state == false
        if (item.state == null) {
            b.root.findActionReceiverOrNull<NovelSectionReceiver>()
                ?.onNovelSectionVisible(item.section)
        }
    }
