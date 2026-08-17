package ceui.pixiv.ui.detail

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.text.HtmlCompat
import androidx.core.view.OneShotPreDrawListener
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.activities.UActivity
import ceui.lisa.activities.VActivity
import ceui.lisa.activities.followUser
import ceui.lisa.activities.followedLabelRes
import ceui.lisa.activities.unfollowUser
import ceui.lisa.adapters.LAdapter
import ceui.lisa.core.Container
import ceui.lisa.core.PageData
import ceui.lisa.databinding.CellCommentPreviewBinding
import ceui.lisa.databinding.SectionV3ArtistBinding
import ceui.lisa.databinding.SectionV3AuthorWorksBinding
import ceui.lisa.databinding.SectionV3CommentsBinding
import ceui.lisa.databinding.SectionV3DescriptionBinding
import ceui.lisa.databinding.SectionV3DetailPanelBinding
import ceui.lisa.databinding.SectionV3HeroBinding
import ceui.lisa.databinding.SectionV3RelatedHeaderBinding
import ceui.lisa.databinding.SectionV3SeriesBinding
import ceui.lisa.databinding.SectionV3StatsBinding
import ceui.lisa.databinding.SectionV3TagsBinding
import ceui.lisa.models.IllustsBean
import ceui.lisa.models.TagsBean
import ceui.lisa.models.UserBean
import ceui.lisa.utils.ClipBoardUtils
import ceui.lisa.utils.Common
import ceui.lisa.utils.GlideUrlChild
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.lisa.utils.SearchTypeUtil
import ceui.loxia.Comment
import ceui.loxia.ObjectPool
import ceui.loxia.ProgressTextButton
import ceui.pixiv.actions.FollowVisibility
import ceui.pixiv.actions.PixivActions
import ceui.pixiv.feeds.FeedCell
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.comments.CommentEmojiSpanner
import ceui.pixiv.ui.comments.translateComment
import ceui.pixiv.ui.user.binding_loadUserIcon
import ceui.pixiv.utils.buildPinnedTagPreviewJson
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import java.text.NumberFormat

/**
 * ArtworkV3 详情页的 header 区块 —— 从 legacy [ArtworkDetailAdapter] 的 10 个 inner ViewHolder
 * 逐个搬成 feeds 的 fullSpan [FeedRenderer]。数据（[FeedItem]）与展示（Renderer）分离,
 * 复用同一批 `SectionV3*` 布局,bind 逻辑近乎逐字对齐旧 VH。
 *
 * 懒加载区块（评论 / 作者其他作品）走 data-in-item(UDF):条目携带数据本身(null=加载中),
 * 首见 null 时回调 Fragment 触发拉取,拉到后经 `feedViewModel.updateItems` 换新条目(数据住
 * FeedViewModel,旋转存活)。相关作品头随初始页一并产出,只有「有 / 无」两态,无独立加载态。
 *
 * 监听器在 `bind` 内注册(而非 [ceui.pixiv.ui.common.staggerIllustRenderer] 那种 `create`):
 * 这些区块每种在整页里**只有一条**(单例 fullSpan header,不是快滚网格的热路径),一次开页只
 * bind 一两次,per-bind 的 lambda 分配可忽略;且关注按钮 / 详情面板折叠等本就是**按当前态**
 * 换绑监听,放 `create` 反而要额外读状态。与被替换的 legacy `ArtworkDetailAdapter` 各 VH 的
 * `onBindViewHolder` 一致。真正的热路径(相关瀑布流卡)仍走 `create`。
 */

// ── 区块条目 ────────────────────────────────────────────────────────────────

class ArtworkHeroItem(val illust: IllustsBean) : FeedItem {
    override val feedKey: Any get() = "artwork_hero"
    override fun equals(other: Any?) = other is ArtworkHeroItem && other.illust === illust
    override fun hashCode() = System.identityHashCode(illust)
}

class ArtworkSeriesItem(val illust: IllustsBean) : FeedItem {
    override val feedKey: Any get() = "artwork_series"
    override fun equals(other: Any?) = other is ArtworkSeriesItem && other.illust === illust
    override fun hashCode() = System.identityHashCode(illust)
}

data class ArtworkDescItem(val caption: String) : FeedItem {
    override val feedKey: Any get() = "artwork_desc"
}

class ArtworkStatsItem(val illust: IllustsBean) : FeedItem {
    override val feedKey: Any get() = "artwork_stats"
    override fun equals(other: Any?) = other is ArtworkStatsItem && other.illust === illust
    override fun hashCode() = System.identityHashCode(illust)
}

class ArtworkTagsItem(val illust: IllustsBean) : FeedItem {
    override val feedKey: Any get() = "artwork_tags"
    override fun equals(other: Any?) = other is ArtworkTagsItem && other.illust === illust
    override fun hashCode() = System.identityHashCode(illust)
}

/** 关注态参与相等性:关注切换时只这条重绑。 */
class ArtworkArtistItem(
    val illust: IllustsBean,
    val isFollowed: Boolean = resolveIsFollowed(illust),
    // 可见性必须进 equals：从画师主页拿到「原来是私密关注」返回时,is_followed 没变,
    // 只有这个字段变了。不带上它 DiffUtil 就判定条目没动,作者栏会一直停在「已关注」。
    val isPrivateFollow: Boolean = resolvePrivateFollow(illust),
) : FeedItem {
    override val feedKey: Any get() = "artwork_artist"
    override fun equals(other: Any?) =
        other is ArtworkArtistItem && other.illust === illust &&
            other.isFollowed == isFollowed && other.isPrivateFollow == isPrivateFollow

    override fun hashCode() =
        (System.identityHashCode(illust) * 31 + isFollowed.hashCode()) * 31 + isPrivateFollow.hashCode()

    companion object {
        // illust.user 只是快照。作者主页打开会 ObjectPool.updateUser 换掉池条目, illust.user 变孤儿。
        // 权威关注态先读池,池空再退回快照。对齐 legacy ArtworkDetailItem.Artist.resolveIsFollowed。
        fun resolveIsFollowed(illust: IllustsBean): Boolean {
            val user = illust.user ?: return false
            return ObjectPool.get<UserBean>(user.id.toLong()).value?.isIs_followed
                ?: user.isIs_followed
        }

        fun resolvePrivateFollow(illust: IllustsBean): Boolean {
            val user = illust.user ?: return false
            return FollowVisibility.isPrivate(user.id.toLong())
        }
    }
}

class ArtworkDetailPanelItem(val illust: IllustsBean) : FeedItem {
    override val feedKey: Any get() = "artwork_detail_panel"
    override fun equals(other: Any?) = other is ArtworkDetailPanelItem && other.illust === illust
    override fun hashCode() = System.identityHashCode(illust)
}

/**
 * 评论预览(懒)。
 *
 * ⚠️ 「拉过没有」是 [fetched] 说了算，**不是** `comments == null`。两者曾经是同一个信号，
 * 结果是这样一条 bug：弱网下评论区块懒加载失败（[SectionLoader] 把它移出 triggered、条目仍
 * `comments == null`）→ 用户在输入框发了一条评论 → [prepend] 把 comments 从 null 变成
 * `[新评论]` → 渲染器的重试触发条件 `comments == null` 从此永不成立 → 本视图生命周期内评论区
 * 只剩用户自己那一条，服务端已有的评论全部看不到。本地插入与「已从服务端拉过」是两件事，分开表达。
 */
data class ArtworkCommentsItem(
    val illustId: Int,
    val illustTitle: String,
    val illustAuthorId: Int,
    val comments: List<Comment>? = null,
    /** 是否已成功从服务端拉过一次评论预览。false = 还该（重）拉，与本地已插入几条无关。 */
    val fetched: Boolean = false,
    /**
     * #592: 评论接口对 app-api 屏蔽的作品永久 404,重试无意义 —— 存下人类可读的报错文案
     * (getHumanReadableMessage,优先服务端 user_message)渲染出来,而不是转圈
     * (转圈=还在等结果,而这里已经有结果了:拉不到)。
     */
    val loadFailedMessage: String? = null,
) : FeedItem {
    override val feedKey: Any get() = "artwork_comments"

    /** 还在等首次拉取结果（本地也没有可展示的评论、也没有定论性失败）→ 渲染加载态。 */
    val isLoading: Boolean
        get() = !fetched && loadFailedMessage == null && comments == null

    /** 懒加载拉到的评论并入(本地已发的排前,按 id 去重)。 */
    fun withComments(loaded: List<Comment>) =
        copy(
            comments = ((comments ?: emptyList()) + loaded).distinctBy { it.id },
            fetched = true,
            loadFailedMessage = null,
        )

    /** 定论性失败(永久 404):不再等、不再重试,渲染报错文案。 */
    fun withLoadFailed(message: String) = copy(loadFailedMessage = message)

    /** 本地新发的顶层评论插到最前(按 id 去重)。刻意不动 [fetched]：发评论不等于拉过评论。 */
    fun prepend(comment: Comment) =
        copy(comments = (listOf(comment) + (comments ?: emptyList())).distinctBy { it.id })
}

/** 作者其他作品(懒):works == null 表示还没拉。 */
data class ArtworkAuthorWorksItem(
    val authorName: String,
    val userId: Int,
    val works: List<IllustsBean>? = null,
) : FeedItem {
    override val feedKey: Any get() = "artwork_author_works"
    override fun equals(other: Any?) =
        other is ArtworkAuthorWorksItem && other.userId == userId && other.works === works

    override fun hashCode() = userId * 31 + System.identityHashCode(works)
}

/** 相关作品头:滚到可见才懒加载(见 [ArtworkSection.RELATED]),加载态 / 空态 / 有相关三态。 */
data class ArtworkRelatedHeaderItem(
    val illustId: Int,
    val illustTitle: String,
    /** null=还没滚到这里(未加载,显加载态) / false=无相关(空态) / true=有相关(显「查看更多」)。 */
    val state: Boolean? = null,
) : FeedItem {
    override val feedKey: Any get() = "artwork_related_header"
}

// ── Renderer ────────────────────────────────────────────────────────────────

internal fun ArtworkV3Fragment.heroRenderer() =
    feedRenderer<ArtworkHeroItem, SectionV3HeroBinding>(
        inflate = SectionV3HeroBinding::inflate,
        fullSpan = true,
    ) { cell ->
        val illust = cell.item.illust
        val ctx = requireContext()
        val b = cell.binding
        b.heroTitle.text = illust.title
        b.heroTitle.setOnLongClickListener { Common.copy(ctx, illust.title.orEmpty()); true }
        b.metaType.text = when (illust.type) {
            "manga" -> ctx.getString(R.string.v3_type_manga)
            "ugoira" -> ctx.getString(R.string.v3_type_ugoira)
            else -> ctx.getString(R.string.v3_type_illustration)
        }
        val ext = page0Extension(illust)
        b.metaExt.isVisible = ext != null
        b.metaExtSep.isVisible = ext != null
        if (ext != null) b.metaExt.text = ext
        b.metaDate.text = Common.getLocalYYYYMMDDHHMMString(illust.create_date)
        b.metaPages.text = if (illust.page_count == 1) ctx.getString(R.string.v3_page_count_one)
        else ctx.getString(R.string.v3_page_count_many, illust.page_count)
    }

internal fun ArtworkV3Fragment.seriesRenderer() =
    feedRenderer<ArtworkSeriesItem, SectionV3SeriesBinding>(
        inflate = SectionV3SeriesBinding::inflate,
        fullSpan = true,
    ) { cell ->
        val illust = cell.item.illust
        val ctx = requireContext()
        val b = cell.binding
        val series = illust.series ?: return@feedRenderer
        b.seriesName.text = series.title
        val d = ctx.resources.displayMetrics.density
        b.seriesStrip.background = palette.seriesStripBg(20f * d)
        b.seriesIcon.background = palette.seriesIconBg(10f * d)
        b.seriesName.setTextColor(palette.seriesStripText)
        b.seriesLabel.setTextColor(palette.seriesStripText)
        b.seriesChevron.setTextColor(palette.seriesStripText)
        b.root.setOnClickListener {
            val intent = Intent(ctx, TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "漫画系列详情")
            intent.putExtra(Params.MANGA_SERIES_ID, series.id)
            ctx.startActivity(intent)
        }
        applyTouchScale(b.root)
    }

internal fun ArtworkV3Fragment.descRenderer() =
    feedRenderer<ArtworkDescItem, SectionV3DescriptionBinding>(
        inflate = SectionV3DescriptionBinding::inflate,
        fullSpan = true,
        recycle = { cell -> clearPendingDescPreDraw(cell.binding) },
    ) { cell ->
        // HTML 解析对长 caption 不便宜;caption 不变(滚动来回重绑)就跳过重解析。
        val b = cell.binding
        if (b.description.tag != cell.item.caption) {
            b.description.tag = cell.item.caption
            // HtmlTextView.setHtml 遇到含 <a> 链接的 caption 会直接吐出空串——经典版
            // FragmentIllust 在 #552 就换掉了,V3 一直没跟上(#960「带链接的简介看不到」)。
            // 同款修法:HtmlCompat 渲染 + LinkMovementMethod 让链接可点。
            descFullCaption = HtmlCompat.fromHtml(
                cell.item.caption, HtmlCompat.FROM_HTML_MODE_COMPACT
            )
            b.description.movementMethod = LinkMovementMethod.getInstance()
        }
        applyDescCollapseState(b)
        b.descToggle.setOnClickListener {
            descExpanded = !descExpanded
            applyDescCollapseState(b)
            if (!descExpanded) scrollDescBackIntoView(b.root)
        }
        b.root.findViewById<View>(R.id.desc_translate).setOnClickListener {
            val plain = HtmlCompat.fromHtml(
                cell.item.caption, HtmlCompat.FROM_HTML_MODE_COMPACT
            ).toString().trim()
            translateComment(plain)
        }
    }

/** 简介折叠阈值(#965):超过这个行数才折,issue 建议 3~5 行,取上限。 */
private const val DESC_COLLAPSED_LINES = 5

/**
 * 超长简介折叠(#965)。折叠不能只靠 maxLines:无 ellipsize 时 layout 仍排全文,被裁的
 * 行只是藏在可视区外,textIsSelectable 的点按(bringPointIntoView)和 LinkMovementMethod
 * 的拖动都会把隐藏行滚出来,表现为「点一下简介顶部/底部,文字自己变了」。所以折叠态在
 * 文本层面截到第 [DESC_COLLAPSED_LINES] 行止:layout 高度 == 可视高度,无处可滚;可见
 * 部分的链接照常可点。全文行数要等 layout 出来才知道,截断和 toggle 可见性都放
 * doOnPreDraw 里做:先摆全文让 TextView 排版,量出超长再换截断文本。每次注册前主动取消
 * 同一个 TextView 上尚未执行的旧回调,并在执行时复核 caption/full 身份,避免同帧重绑时旧
 * caption 的回调覆盖新内容。
 */
private fun ArtworkV3Fragment.applyDescCollapseState(b: SectionV3DescriptionBinding) {
    val full = descFullCaption ?: return
    val expectedCaption = b.description.tag
    b.description.maxLines = if (descExpanded) Int.MAX_VALUE else DESC_COLLAPSED_LINES
    b.description.text = full
    b.description.scrollTo(0, 0)
    b.descToggle.setText(
        if (descExpanded) R.string.v3_desc_collapse else R.string.v3_desc_expand
    )
    clearPendingDescPreDraw(b)
    lateinit var request: OneShotPreDrawListener
    request = b.description.doOnPreDraw {
        // 新一次 bind/toggle 已取代本次请求时绝不再碰当前 holder。
        if (b.description.getTag(R.id.v3_desc_predraw_listener) !== request) {
            return@doOnPreDraw
        }
        b.description.setTag(R.id.v3_desc_predraw_listener, null)
        if (b.description.tag != expectedCaption || descFullCaption !== full) {
            return@doOnPreDraw
        }
        val layout = b.description.layout ?: return@doOnPreDraw
        val overflow = layout.lineCount > DESC_COLLAPSED_LINES
        b.descToggle.isVisible = overflow
        if (!descExpanded && overflow) {
            // 即使平台 Layout 给出异常 offset,也不能让展示逻辑把详情页带崩。
            val end = layout.getLineEnd(DESC_COLLAPSED_LINES - 1).coerceIn(0, full.length)
            b.description.text = full.subSequence(0, end).trimEnd()
        }
    }
    b.description.setTag(R.id.v3_desc_predraw_listener, request)
}

private fun clearPendingDescPreDraw(b: SectionV3DescriptionBinding) {
    (b.description.getTag(R.id.v3_desc_predraw_listener) as? OneShotPreDrawListener)
        ?.removeListener()
    b.description.setTag(R.id.v3_desc_predraw_listener, null)
}

internal fun ArtworkV3Fragment.statsRenderer() =
    feedRenderer<ArtworkStatsItem, SectionV3StatsBinding>(
        inflate = SectionV3StatsBinding::inflate,
        fullSpan = true,
        create = { cell ->
            val wrap = cell.binding.statBookmarkWrap
            applyTouchScale(wrap)
            wrap.setOnClickListener {
                val illust = cell.item.illust
                val ctx = wrap.context
                ctx.startActivity(
                    Intent(ctx, TemplateActivity::class.java).apply {
                        putExtra(Params.CONTENT, illust)
                        putExtra(TemplateActivity.EXTRA_FRAGMENT, "喜欢这个作品的用户")
                    }
                )
            }
        },
    ) { cell ->
        val illust = cell.item.illust
        val fmt = NumberFormat.getNumberInstance()
        cell.binding.statViews.text = fmt.format(illust.total_view)
        cell.binding.statBookmarks.text = fmt.format(illust.total_bookmarks)
    }

internal fun ArtworkV3Fragment.tagsRenderer() =
    feedRenderer<ArtworkTagsItem, SectionV3TagsBinding>(
        inflate = SectionV3TagsBinding::inflate,
        fullSpan = true,
    ) { cell ->
        val illust = cell.item.illust
        val b = cell.binding
        b.tagsFlow.searchIndex = 0 // illust tab
        b.tagsFlow.setJavaTags(illust.tags.orEmpty())
        b.synonymMatch.setWorkTags(illust.tags.orEmpty())
        b.tagsFlow.onPinTag = { name, translated, newPinned ->
            val tagBean = TagsBean().apply {
                this.name = name
                this.translated_name = translated
            }
            val previewJson = if (newPinned) buildPinnedTagPreviewJson(tagBean, illust) else null
            PixivOperate.insertPinnedSearchHistory(
                name, SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD, newPinned, previewJson,
            )
            Common.showToast(R.string.operate_success)
        }
    }

internal fun ArtworkV3Fragment.artistRenderer() =
    feedRenderer<ArtworkArtistItem, SectionV3ArtistBinding>(
        inflate = SectionV3ArtistBinding::inflate,
        fullSpan = true,
    ) { cell ->
        val illust = cell.item.illust
        val ctx = requireContext()
        val b = cell.binding
        val user = illust.user ?: return@feedRenderer
        b.artistName.text = user.name
        b.artistHandle.text = "@${user.account ?: ""}"

        val openUser = View.OnClickListener {
            val intent = Intent(ctx, UActivity::class.java)
            intent.putExtra(Params.USER_ID, user.id)
            ctx.startActivity(intent)
        }
        b.artistCard.setOnClickListener(openUser)
        b.artistName.setOnClickListener(openUser)
        b.artistName.setOnLongClickListener { Common.copy(ctx, user.name.orEmpty()); true }
        b.artistHandle.setOnClickListener(openUser)
        b.artistHandle.setOnLongClickListener {
            Common.copy(ctx, b.artistHandle.text?.toString().orEmpty()); true
        }
        illustGlide.load(GlideUtil.getUrl(user.profile_image_urls?.medium))
            .error(R.drawable.no_profile)
            .into(b.artistAvatar)

        applyTouchScale(b.artistCard)

        bindArtistFollowState(b, user)
        b.artistBio.isVisible = !user.comment.isNullOrBlank()
        if (b.artistBio.isVisible) b.artistBio.text = user.comment
    }

private fun ArtworkV3Fragment.bindArtistFollowState(b: SectionV3ArtistBinding, user: UserBean) {
    val ctx = requireContext()
    val isFollowed = ObjectPool.get<UserBean>(user.id.toLong()).value?.isIs_followed
        ?: user.isIs_followed
    if (isFollowed) {
        b.followBtn.text = ctx.getString(followedLabelRes(user.id))
        palette.applyUnfollowBtn(b.followBtn)
        b.followBtn.setOnClick { unfollowUser(it as ProgressTextButton, user.id) }
        b.followBtn.setOnLongClickListener(null)
        b.followBtn.isLongClickable = false
    } else {
        b.followBtn.text = ctx.getString(R.string.follow)
        palette.applyFollowBtn(b.followBtn)
        b.followBtn.setTextColor(Color.WHITE)
        b.followBtn.setOnClick { followUser(it as ProgressTextButton, user.id, PixivActions.defaultFollowRestrict()) }
        b.followBtn.setOnLongClickListener {
            followUser(b.followBtn, user.id, Params.TYPE_PRIVATE); true
        }
    }
}

internal fun ArtworkV3Fragment.detailPanelRenderer() =
    feedRenderer<ArtworkDetailPanelItem, SectionV3DetailPanelBinding>(
        inflate = SectionV3DetailPanelBinding::inflate,
        fullSpan = true,
    ) { cell ->
        val illust = cell.item.illust
        val b = cell.binding
        // 只在换了作品时重建 chips(~30 个 view + 一堆 getString);单例 header,滚动来回重绑不重建。
        if (b.detailGrid.tag !== illust) {
            b.detailGrid.tag = illust
            b.detailGrid.removeAllViews()
            buildDetailChips(b, illust)
        }
        // 展开态归 Fragment 字段(滚走再滚回不重置);绑定时按当前态还原,不放动画。
        b.detailGrid.isVisible = detailPanelExpanded
        b.detailArrow.rotation = if (detailPanelExpanded) 0f else 180f
        b.detailHeader.setOnClickListener {
            val next = !detailPanelExpanded
            detailPanelExpanded = next
            val grid = b.detailGrid
            val arrow = b.detailArrow
            if (!next) {
                grid.animate().alpha(0f).translationY(-12.ppppx.toFloat()).setDuration(250)
                    .setInterpolator(DecelerateInterpolator(2f))
                    .withEndAction { grid.isVisible = false; grid.translationY = 0f }.start()
                arrow.animate().rotation(180f).setDuration(300).start()
            } else {
                grid.alpha = 0f; grid.translationY = -12.ppppx.toFloat(); grid.isVisible = true
                grid.animate().alpha(1f).translationY(0f).setDuration(350)
                    .setInterpolator(DecelerateInterpolator(2f)).start()
                arrow.animate().rotation(0f).setDuration(300).start()
            }
        }
    }

private fun ArtworkV3Fragment.buildDetailChips(b: SectionV3DetailPanelBinding, illust: IllustsBean) {
    val ctx = requireContext()
    fun s(resId: Int) = ctx.getString(resId)
    val chips = listOf(
        s(R.string.v3_detail_artwork_id) to illust.id.toString(),
        s(R.string.v3_detail_user_id) to (illust.user?.id?.toString() ?: "--"),
        s(R.string.v3_detail_type) to when (illust.type) {
            "manga" -> s(R.string.v3_type_manga)
            "ugoira" -> s(R.string.v3_type_ugoira)
            else -> s(R.string.v3_type_illustration)
        },
        s(R.string.v3_detail_resolution) to "${illust.width} × ${illust.height}",
        s(R.string.v3_detail_pages) to illust.page_count.toString(),
        s(R.string.v3_detail_ai) to if (illust.illust_ai_type == 2) s(R.string.v3_detail_ai_yes)
        else s(R.string.v3_detail_ai_no),
        s(R.string.v3_detail_restriction) to when {
            illust.x_restrict == 1 -> "R-18"
            illust.x_restrict == 2 -> "R-18G"
            else -> s(R.string.v3_detail_all_ages)
        },
        s(R.string.v3_detail_published) to Common.getLocalYYYYMMDDHHMMString(illust.create_date),
    )
    for (i in chips.indices step 2) {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            if (i > 0) layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 8.ppppx }
        }
        row.addView(createDetailChip(ctx, chips[i].first, chips[i].second, illust))
        if (i + 1 < chips.size) {
            row.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(8.ppppx, 1)
            })
            row.addView(createDetailChip(ctx, chips[i + 1].first, chips[i + 1].second, illust))
        }
        b.detailGrid.addView(row)
    }
}

private fun ArtworkV3Fragment.createDetailChip(
    ctx: android.content.Context,
    label: String,
    value: String,
    illust: IllustsBean,
): LinearLayout {
    return LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.v3_detail_chip_bg)
        setPadding(12.ppppx, 10.ppppx, 12.ppppx, 10.ppppx)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        addView(TextView(ctx).apply {
            text = label.uppercase(); textSize = 9f
            setTextColor(ctx.getColor(R.color.v3_text_3)); letterSpacing = 0.08f; alpha = 0.7f
        })
        val artworkIdLabel = ctx.getString(R.string.v3_detail_artwork_id)
        val userIdLabel = ctx.getString(R.string.v3_detail_user_id)
        val aiLabel = ctx.getString(R.string.v3_detail_ai)
        val restrictionLabel = ctx.getString(R.string.v3_detail_restriction)
        addView(TextView(ctx).apply {
            text = value; textSize = 13f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setTypeface(
                if (label == artworkIdLabel || label == userIdLabel) Typeface.MONOSPACE else typeface,
                Typeface.BOLD,
            )
            setTextColor(
                when {
                    label == artworkIdLabel || label == userIdLabel -> palette.textAccent
                    label == aiLabel && illust.illust_ai_type == 2 -> ctx.getColor(R.color.v3_purple)
                    label == aiLabel -> ctx.getColor(R.color.v3_green)
                    label == restrictionLabel && illust.x_restrict > 0 -> ctx.getColor(R.color.v3_pink)
                    label == restrictionLabel -> ctx.getColor(R.color.v3_blue)
                    else -> ctx.getColor(R.color.v3_text_1)
                },
            )
            alpha = if (label == artworkIdLabel || label == userIdLabel) 1f else 0.8f
        })
        setOnClickListener { Common.copy(ctx, value) }
    }
}

internal fun ArtworkV3Fragment.commentsRenderer() =
    feedRenderer<ArtworkCommentsItem, SectionV3CommentsBinding>(
        inflate = SectionV3CommentsBinding::inflate,
        fullSpan = true,
        attach = { cell ->
            // 按「拉过没有」判定，不是按「列表空不空」——本地发出的评论不能顶替服务端拉取
            // （见 [ArtworkCommentsItem.fetched]）。
            if (!cell.item.fetched) onSectionVisible(ArtworkSection.COMMENTS)
        },
    ) { cell ->
        val item = cell.item
        val ctx = requireContext()
        val b = cell.binding

        fun openCommentList() {
            val intent = Intent(ctx, TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "相关评论")
            intent.putExtra(Params.ILLUST_ID, item.illustId)
            intent.putExtra(Params.ILLUST_TITLE, item.illustTitle)
            ctx.startActivity(intent)
        }

        b.commentsMore.setTextColor(palette.textAccent)
        b.commentsMore.setOnClick { openCommentList() }

        val density = ctx.resources.displayMetrics.density
        b.addCommentEntry.background = palette.settingsCardBg(22f * density, (1 * density).toInt())
        b.addCommentAvatar.binding_loadUserIcon(SessionManager.loggedInUser)
        b.addCommentEntry.setOnClick { showComposer() }

        renderCommentsPreview(b, item)
    }

private fun ArtworkV3Fragment.renderCommentsPreview(
    b: SectionV3CommentsBinding,
    item: ArtworkCommentsItem,
) {
    val ctx = requireContext()
    val illustAuthorId = item.illustAuthorId
    val isLoading = item.isLoading
    b.commentsLoading.isVisible = isLoading
    b.commentsList.isVisible = !isLoading
    if (isLoading) {
        b.commentsEmpty.isVisible = false
        return
    }
    // 非加载态时 comments 可能仍是 null(定论性失败),统一空列表处理。
    val comments = item.comments ?: emptyList()
    b.commentsEmpty.isVisible = comments.isEmpty()
    // 同一个 TextView 兼任空态和失败态:「还没有评论」是拉成功且为空,报错文案是拉不到(#592)
    b.commentsEmpty.text = item.loadFailedMessage ?: ctx.getString(R.string.v3_no_comments_yet)

    // 评论列表实例不变(滚动来回重绑)就跳过重新 inflate 三张预览卡;发新评论 / 首次拉到会换实例。
    if (b.commentsList.tag === comments) return
    b.commentsList.tag = comments

    b.commentsList.removeAllViews()

    val inflater = android.view.LayoutInflater.from(ctx)
    val accent = palette.textAccent
    comments.forEach { comment ->
        val cellB = CellCommentPreviewBinding.inflate(inflater, b.commentsList, false)
        (cellB.root.layoutParams as ViewGroup.MarginLayoutParams).topMargin =
            if (b.commentsList.childCount > 0) 8.ppppx else 0

        cellB.userName.text = comment.user.name
        cellB.commentTime.text = comment.displayCommentDate()

        val isArthur = illustAuthorId.toLong() == comment.user.id
        cellB.arthurLabel.isVisible = isArthur
        if (isArthur) {
            cellB.arthurLabel.backgroundTintList = ColorStateList.valueOf(palette.alpha15)
            cellB.arthurLabel.setTextColor(accent)
        }
        cellB.userIcon.borderColor =
            if (isArthur) accent else ctx.getColor(R.color.v3_border_2)
        comment.user.profile_image_urls?.medium?.let {
            illustGlide.load(GlideUrlChild(it)).circleCrop().into(cellB.userIcon)
        }

        val stampUrl = comment.stamp?.stamp_url
        cellB.commentStamp.isVisible = stampUrl != null
        cellB.commentContent.isVisible = stampUrl == null
        if (stampUrl != null) {
            illustGlide.load(GlideUrlChild(stampUrl)).into(cellB.commentStamp)
        } else {
            cellB.commentContent.text = CommentEmojiSpanner.format(
                ctx, comment.comment, cellB.commentContent.textSize.toInt(),
            )
        }

        cellB.root.setOnLongClickListener {
            val text = comment.comment
            showV3Menu("PreviewCommentMenu") {
                if (!text.isNullOrBlank()) {
                    item(ctx.getString(R.string.string_173), R.drawable.baseline_content_copy_24) {
                        ClipBoardUtils.putTextIntoClipboard(ctx, text)
                    }
                    item(ctx.getString(R.string.string_translate_caption), R.drawable.ic_baseline_translate_24) {
                        translateComment(text)
                    }
                }
                item(ctx.getString(R.string.string_174), R.drawable.ic_supervisor_account_black_24dp) {
                    val intent = Intent(ctx, UActivity::class.java)
                    intent.putExtra(Params.USER_ID, comment.user.id.toInt())
                    ctx.startActivity(intent)
                }
            }
            true
        }

        b.commentsList.addView(cellB.root)
    }
}

internal fun ArtworkV3Fragment.authorWorksRenderer() =
    feedRenderer<ArtworkAuthorWorksItem, SectionV3AuthorWorksBinding>(
        inflate = SectionV3AuthorWorksBinding::inflate,
        fullSpan = true,
        attach = { cell ->
            if (cell.item.works == null) onSectionVisible(ArtworkSection.AUTHOR_WORKS)
        },
    ) { cell ->
        val item = cell.item
        val ctx = requireContext()
        val b = cell.binding
        b.authorWorksLabel.text =
            ctx.getString(R.string.v3_author_works, item.authorName).uppercase()
        b.authorWorksSeeAll.setTextColor(palette.textAccent)
        b.authorWorksSeeAll.setOnClickListener {
            val intent = Intent(ctx, TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "插画作品")
            intent.putExtra(Params.USER_ID, item.userId)
            ctx.startActivity(intent)
        }
        renderAuthorWorks(b, item.works)
    }

private fun ArtworkV3Fragment.renderAuthorWorks(
    b: SectionV3AuthorWorksBinding,
    works: List<IllustsBean>?,
) {
    val ctx = requireContext()
    if (works == null) {
        b.authorWorksLoading.isVisible = true
        b.authorWorksRv.isVisible = false
        b.authorWorksSeeAll.isVisible = false
        return
    }
    b.authorWorksLoading.isVisible = false
    if (works.isEmpty()) {
        b.authorWorksLabel.isVisible = false
        b.authorWorksRv.isVisible = false
        b.authorWorksSeeAll.isVisible = false
        return
    }
    b.authorWorksLabel.isVisible = true
    b.authorWorksRv.isVisible = true
    b.authorWorksSeeAll.isVisible = true

    if (b.authorWorksRv.tag !== works) {
        b.authorWorksRv.tag = works
        if (b.authorWorksRv.layoutManager == null) {
            b.authorWorksRv.layoutManager =
                LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
            b.authorWorksRv.addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(
                    outRect: android.graphics.Rect, view: View,
                    parent: RecyclerView, state: RecyclerView.State,
                ) {
                    // 末卡不加尾距,滚到最右时正好停在 RV 的 12dp 内边距处
                    if (parent.getChildAdapterPosition(view) < state.itemCount - 1) {
                        outRect.right = 8.ppppx
                    }
                }
            })
        }
        val worksList = works.toMutableList()
        val lAdapter = LAdapter(worksList, ctx)
        lAdapter.setOnItemClickListener { _, position, _ ->
            val pageData = PageData(worksList)
            Container.get().addPageToMap(pageData)
            val intent = Intent(ctx, VActivity::class.java)
            intent.putExtra(Params.POSITION, position)
            intent.putExtra(Params.PAGE_UUID, pageData.uuid)
            ctx.startActivity(intent)
        }
        b.authorWorksRv.adapter = lAdapter
        val lp = b.authorWorksRv.layoutParams
        lp.height = lAdapter.imageSize + ctx.resources.getDimensionPixelSize(R.dimen.sixteen_dp)
        b.authorWorksRv.layoutParams = lp
    }
}

internal fun ArtworkV3Fragment.relatedHeaderRenderer() =
    feedRenderer<ArtworkRelatedHeaderItem, SectionV3RelatedHeaderBinding>(
        inflate = SectionV3RelatedHeaderBinding::inflate,
        fullSpan = true,
        attach = { cell ->
            if (cell.item.state == null) onSectionVisible(ArtworkSection.RELATED)
        },
    ) { cell ->
        val item = cell.item
        val ctx = requireContext()
        val b = cell.binding
        b.relatedSeeMore.setTextColor(palette.textAccent)
        b.relatedSeeMore.setOnClick {
            val intent = Intent(ctx, TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "相关作品")
            intent.putExtra(Params.ILLUST_ID, item.illustId)
            intent.putExtra(Params.ILLUST_TITLE, item.illustTitle)
            ctx.startActivity(intent)
        }
        b.relatedLoadingContainer.isVisible = item.state == null
        b.relatedSeeMore.isVisible = item.state == true
        b.relatedEmpty.isVisible = item.state == false
    }

// ── helpers ───────────────────────────────────────────────────────────────

/** 第 0 P 原图 URL 的文件后缀(大写,如 PNG / JPG);拿不到返回 null。对齐旧 VH。 */
private fun page0Extension(illust: IllustsBean): String? {
    val url = if (illust.page_count <= 1) {
        illust.meta_single_page?.original_image_url
    } else {
        illust.meta_pages?.getOrNull(0)?.image_urls?.original
    }
    val clean = url?.substringBefore('?')?.substringBefore('#') ?: return null
    val dot = clean.lastIndexOf('.')
    if (dot < 0 || dot == clean.length - 1) return null
    val ext = clean.substring(dot + 1)
    if (ext.length > 5 || ext.contains('/')) return null
    return ext.uppercase()
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
