package ceui.pixiv.ui.fanbox

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.View
import androidx.core.net.toUri
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.databinding.CellFanboxBodyImageBinding
import ceui.lisa.databinding.CellFanboxBodyLinkBinding
import ceui.lisa.databinding.CellFanboxBodyTextBinding
import ceui.lisa.databinding.CellFanboxCommentBinding
import ceui.lisa.databinding.CellFanboxDetailHeaderBinding
import ceui.lisa.databinding.CellFanboxNoticeBinding
import ceui.lisa.databinding.CellFanboxPlanBinding
import ceui.lisa.databinding.CellFanboxSectionBinding
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.lisa.utils.GlideUtil
import ceui.loxia.Client
import ceui.loxia.FanboxBlockLink
import ceui.loxia.FanboxBlockStyle
import ceui.loxia.FanboxComment
import ceui.loxia.FanboxEmbed
import ceui.loxia.FanboxFile
import ceui.loxia.FanboxImage
import ceui.loxia.FanboxPlan
import ceui.loxia.FanboxPost
import ceui.loxia.FanboxUrlEmbed
import ceui.loxia.FanboxWebBridge
import ceui.loxia.appServices
import ceui.loxia.fetchFanboxPostInfo
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.ui.common.ImageUrlViewer
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.clearGlideOnRecycle
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import ceui.lisa.view.LinearItemDecoration
import com.bumptech.glide.Glide

/**
 * FANBOX 帖子详情(原生)。
 *
 * 网页版详情页上有的东西这里都做了:正文(post.info)、帖子元数据、赞助方案
 * (plan.listCreator,就是付费墙那个「方案列表」背后的数据)、完整评论区(含楼中楼)。
 *
 * 正文那条路要绕一下:`post.info` 被 Cloudflare 单独挡了非浏览器客户端,OkHttp 一律 403,
 * 所以它走 [ceui.loxia.FanboxWebBridge](不上屏的 WebView)发。拿不到时(没登录、被挡、
 * 超时)退回 `post.get` 只渲染元数据,并把正文位置换成「在网页中打开」。
 *
 * 受限帖子(没赞助到档)服务端本来就不下发 body,那是正常态,走的是赞助方案那一段。
 */
class FanboxPostDetailFragment : FeedFragment(R.layout.fragment_toolbar_feed) {

    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)

    /** 正文图片按接口给的宽高比撑高度用;首次布局后填,拿不到就退回屏宽估算。 */
    private var contentWidth = 0

    private val postId: String by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(ARG_POST_ID).orEmpty()
    }

    override val feedViewModel by feedViewModels<String> {
        // 零捕获约定:把 Fragment 上的东西(id、字符串)全取成局部 val 再进 source,
        // 别让 source 捕获 Fragment —— 它被 VM 长期持有。
        val id = postId
        val plansTitle = getString(R.string.fanbox_section_plans)
        val commentsTitle = getString(R.string.fanbox_section_comments)
        val commentsLocked = getString(R.string.fanbox_comments_locked)
        // 桥是进程级服务(应用 Context 持有),捕获它不会把 Fragment 带进 VM。
        val bridge = requireContext().appServices().fanboxWebBridge
        FeedSource { _ -> loadFanboxPostDetail(bridge, id, plansTitle, commentsTitle, commentsLocked) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding, feedBinding.feedListView)
        binding.toolbarTitle.text = getString(R.string.fanbox_entry)
    }

    override fun onListReady(listView: RecyclerView) {
        listView.addItemDecoration(LinearItemDecoration(12.ppppx))
        // 正文图片的高度要在 bind 里就定好(见 cell_fanbox_body_image 的注释),
        // 而那时拿不到自己的宽度 —— 列表宽度对每张图都一样,首次布局时量一次存着。
        listView.doOnLayout { contentWidth = it.width - 2 * 12.ppppx }
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(
            headerRenderer(),
            sectionRenderer(),
            noticeRenderer(),
            bodyTextRenderer(),
            bodyImageRenderer(),
            bodyLinkRenderer(),
            planRenderer(),
            commentRenderer(),
        )
    }

    private fun headerRenderer() =
        feedRenderer<FanboxDetailHeaderItem, CellFanboxDetailHeaderBinding>(
            inflate = CellFanboxDetailHeaderBinding::inflate,
            create = { cell ->
                cell.binding.root.clipToOutline = true
                cell.binding.bodyNotice.setOnClick { openPostInWeb(cell.item.post) }
            },
            recycle = { cell ->
                cell.binding.cover.clearGlideOnRecycle()
                cell.binding.creatorIcon.clearGlideOnRecycle()
            },
        ) { cell ->
            val post = cell.item.post
            cell.binding.title.text = post.title.orEmpty()
            cell.binding.creatorName.text = post.user?.name.orEmpty()
            cell.binding.publishedTime.text = formatFanboxTime(post.publishedDatetime)
            cell.binding.feeBadge.text = if (post.feeRequired > 0) {
                getString(R.string.fanbox_fee_badge, post.feeRequired)
            } else {
                getString(R.string.fanbox_fee_free)
            }
            cell.binding.badgeR18.isVisible = post.hasAdultContent
            // 正文渲染出来了 excerpt 就是同一段话的重复,收掉。
            val excerpt = if (cell.item.showWebNotice) post.excerpt.orEmpty() else ""
            cell.binding.excerpt.isVisible = excerpt.isNotEmpty()
            cell.binding.excerpt.text = excerpt
            val tags = post.tags.orEmpty().filter { it.isNotEmpty() }
            cell.binding.tags.isVisible = tags.isNotEmpty()
            cell.binding.tags.text = tags.joinToString(" ") { "#$it" }
            cell.binding.likeCount.text = post.likeCount.toString()
            cell.binding.commentCount.text = post.commentCount.toString()
            // 正文渲染出来了就别再挂「去网页看正文」——那是取不到 body 时的兜底。
            cell.binding.bodyNotice.isVisible = cell.item.showWebNotice
            val coverUrl = post.coverUrl
            cell.binding.cover.isVisible = coverUrl.isNotEmpty()
            if (coverUrl.isNotEmpty()) {
                Glide.with(cell.binding.cover)
                    .load(GlideUtil.getUrl(coverUrl))
                    .placeholder(R.color.v3_surface_2)
                    .into(cell.binding.cover)
            }
            Glide.with(cell.binding.creatorIcon)
                .load(GlideUtil.getUrl(post.user?.iconUrl))
                .placeholder(R.color.v3_surface_2)
                .into(cell.binding.creatorIcon)
        }

    private fun sectionRenderer() = feedRenderer<FanboxSectionItem, CellFanboxSectionBinding>(
        inflate = CellFanboxSectionBinding::inflate,
    ) { cell ->
        cell.binding.sectionTitle.text = cell.item.title
    }

    private fun noticeRenderer() = feedRenderer<FanboxNoticeItem, CellFanboxNoticeBinding>(
        inflate = CellFanboxNoticeBinding::inflate,
    ) { cell ->
        cell.binding.noticeText.text = cell.item.text
    }

    /** 段落 / 小标题。两者只差字号和字重,共用一个 cell。 */
    private fun bodyTextRenderer() = feedRenderer<FanboxBodyTextItem, CellFanboxBodyTextBinding>(
        inflate = CellFanboxBodyTextBinding::inflate,
    ) { cell ->
        val view = cell.binding.bodyText
        if (cell.item.isHeader) {
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            view.setTypeface(null, Typeface.BOLD)
        } else {
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            view.setTypeface(null, Typeface.NORMAL)
        }
        val links = cell.item.links
        val text = decorate(cell.item.text, links, cell.item.styles)
        if (links.isEmpty()) {
            // 回收复用:上一条如果带链接,movementMethod 还挂着,不清掉这条就没法选中复制。
            view.movementMethod = null
            view.setTextIsSelectable(true)
            view.text = text
        } else {
            // 可选中和可点链接在一个 TextView 上互斥(movementMethod 只能有一个),
            // 有链接的段落让链接赢 —— 点不开的链接比复制不了更让人困惑。
            view.setTextIsSelectable(false)
            view.text = text
            view.movementMethod = LinkMovementMethod.getInstance()
        }
    }

    private fun bodyImageRenderer() = feedRenderer<FanboxBodyImageItem, CellFanboxBodyImageBinding>(
        inflate = CellFanboxBodyImageBinding::inflate,
        create = { cell ->
            cell.binding.root.clipToOutline = true
            cell.binding.root.setOnClick {
                val image = cell.item.image
                ImageUrlViewer.open(
                    requireContext(),
                    image.originalUrl ?: image.thumbnailUrl.orEmpty(),
                    saveName = "fanbox_${postId}_${image.id.orEmpty()}",
                )
            }
        },
        recycle = { cell -> cell.binding.bodyImage.clearGlideOnRecycle() },
    ) { cell ->
        val image = cell.item.image
        val width = contentWidth.takeIf { it > 0 }
            ?: (resources.displayMetrics.widthPixels - 2 * 12.ppppx)
        val height = if (image.width > 0 && image.height > 0) {
            // 长图直接按比例撑会做出一个几千 px 高的 item,封顶到 4 屏宽;
            // 真要看细节点开二级大图页,那边是可缩放的。
            (width.toLong() * image.height / image.width).toInt().coerceAtMost(width * 4)
        } else {
            width * 3 / 4
        }
        cell.binding.bodyImage.updateLayoutParams { this.height = height }
        Glide.with(cell.binding.bodyImage)
            .load(GlideUtil.getUrl(image.thumbnailUrl ?: image.originalUrl))
            .placeholder(R.color.v3_surface_2)
            .into(cell.binding.bodyImage)
    }

    /** 附件和站外嵌入共用:两者对原生都只是「一个能点开的链接」。 */
    private fun bodyLinkRenderer() = feedRenderer<FanboxBodyLinkItem, CellFanboxBodyLinkBinding>(
        inflate = CellFanboxBodyLinkBinding::inflate,
        create = { cell ->
            cell.binding.root.clipToOutline = true
            cell.binding.root.setOnClick { openLink(cell.item.url) }
        },
    ) { cell ->
        cell.binding.linkTitle.text = cell.item.title
        val subtitle = cell.item.subtitle
        cell.binding.linkSubtitle.isVisible = subtitle.isNotEmpty()
        cell.binding.linkSubtitle.text = subtitle
    }

    private fun planRenderer() = feedRenderer<FanboxPlanItem, CellFanboxPlanBinding>(
        inflate = CellFanboxPlanBinding::inflate,
        create = { cell ->
            cell.binding.root.clipToOutline = true
            cell.binding.root.setOnClick { openPlanInWeb(cell.item.plan) }
        },
        recycle = { cell -> cell.binding.planCover.clearGlideOnRecycle() },
    ) { cell ->
        val plan = cell.item.plan
        cell.binding.planTitle.text = plan.title.orEmpty()
        cell.binding.planFee.text = getString(R.string.fanbox_fee_badge, plan.fee)
        val desc = plan.description.orEmpty().replace('\r', ' ').trim()
        cell.binding.planDesc.isVisible = desc.isNotEmpty()
        cell.binding.planDesc.text = desc
        val cover = plan.coverImageUrl.orEmpty()
        cell.binding.planCover.isVisible = cover.isNotEmpty()
        if (cover.isNotEmpty()) {
            Glide.with(cell.binding.planCover)
                .load(GlideUtil.getUrl(cover))
                .placeholder(R.color.v3_surface_2)
                .into(cell.binding.planCover)
        }
    }

    private fun commentRenderer() = feedRenderer<FanboxCommentItem, CellFanboxCommentBinding>(
        inflate = CellFanboxCommentBinding::inflate,
        create = { cell -> cell.binding.root.clipToOutline = true },
        recycle = { cell -> cell.binding.commentIcon.clearGlideOnRecycle() },
    ) { cell ->
        val c = cell.item.comment
        cell.binding.commentUser.text = c.user?.name.orEmpty()
        cell.binding.commentTime.text = formatFanboxTime(c.createdDatetime)
        cell.binding.commentBody.text = c.body.orEmpty()
        cell.binding.commentLike.isVisible = c.likeCount > 0
        cell.binding.commentLike.text = c.likeCount.toString()
        // 楼中楼靠左缩进表达层级(服务端已把 replies 嵌好,列表结构保持扁平)。
        cell.binding.root.updateLayoutParams<RecyclerView.LayoutParams> {
            marginStart = if (cell.item.depth > 0) 24.ppppx else 0
        }
        Glide.with(cell.binding.commentIcon)
            .load(GlideUtil.getUrl(c.user?.iconUrl))
            .placeholder(R.color.v3_surface_2)
            .into(cell.binding.commentIcon)
    }

    private fun openPostInWeb(post: FanboxPost) {
        val creatorId = post.creatorId.orEmpty()
        openFanboxWeb(
            if (creatorId.isEmpty()) "https://www.fanbox.cc/"
            else "https://www.fanbox.cc/@$creatorId/posts/${post.id}"
        )
    }

    private fun openPlanInWeb(plan: FanboxPlan) {
        val creatorId = plan.creatorId.orEmpty()
        openFanboxWeb(
            if (creatorId.isEmpty()) "https://www.fanbox.cc/"
            else "https://www.fanbox.cc/@$creatorId/plans"
        )
    }

    /**
     * 正文里的链接。fanbox.cc 自己的留在 app 里(那套 WebView 带着登录态),
     * 站外的丢给系统 —— 附件下载、推特、YouTube 都是外部应用处理更合适。
     */
    private fun openLink(url: String) {
        if (url.isEmpty()) return
        if (url.toUri().host?.endsWith("fanbox.cc") == true) {
            openFanboxWeb(url)
            return
        }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    }

    /**
     * 把接口给的 offset/length 区间(链接、加粗)铺成 Spannable。
     * 区间越界就丢掉那一段 —— 服务端偶尔会给出超过文本长度的 offset,不能让它炸掉整段。
     */
    private fun decorate(
        text: String,
        links: List<FanboxBlockLink>,
        styles: List<FanboxBlockStyle>,
    ): CharSequence {
        if (links.isEmpty() && styles.isEmpty()) return text
        val builder = SpannableStringBuilder(text)
        fun span(what: Any, offset: Int, length: Int) {
            val end = offset + length
            if (offset < 0 || length <= 0 || end > text.length) return
            builder.setSpan(what, offset, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        styles.forEach { style ->
            if (style.type == STYLE_BOLD) span(StyleSpan(Typeface.BOLD), style.offset, style.length)
        }
        links.forEach { link ->
            val url = link.url.orEmpty()
            if (url.isEmpty()) return@forEach
            span(object : ClickableSpan() {
                override fun onClick(widget: View) = openLink(url)
            }, link.offset, link.length)
        }
        return builder
    }

    companion object {
        const val ARG_POST_ID = "fanbox_post_id"

        @JvmStatic
        fun newInstance(postId: String): FanboxPostDetailFragment {
            return FanboxPostDetailFragment().apply {
                arguments = Bundle().apply { putString(ARG_POST_ID, postId) }
            }
        }
    }
}

/**
 * 拼一页详情:元数据 + 正文 → (受限时)赞助方案 → 评论。几个请求都可能各自失败,
 * 但只有元数据是必须的 —— 方案/评论拿不到就少一段,不该让整页塌掉。
 *
 * 正文优先走 WebView 的 post.info(它同时带着完整元数据,拿到就不用再打 post.get);
 * 被挡时才退回 post.get,那条路只有元数据。
 */
private suspend fun loadFanboxPostDetail(
    bridge: FanboxWebBridge,
    postId: String,
    plansTitle: String,
    commentsTitle: String,
    commentsLocked: String,
): FeedPage<String> {
    val fullPost = fetchFanboxPostInfo(bridge, postId)
    val post = fullPost
        ?: Client.fanboxApi.postGet(postId).body?.post
        ?: return FeedPage(emptyList(), null)

    val bodyItems = bodyItemsOf(post)
    // 受限帖子服务端本来就不给 body,那种情况下引导去网页也没用 —— 网页同样是付费墙,
    // 下面会给出赞助方案。只有「本该有正文却没取到」才挂那条兜底。
    val items = mutableListOf<FeedItem>(
        FanboxDetailHeaderItem(post, showWebNotice = bodyItems.isEmpty() && !post.isRestricted)
    )
    items += bodyItems

    // 付费墙内容才需要看方案 —— 已经能读的帖子塞一堆方案卡是噪音。
    val creatorId = post.creatorId.orEmpty()
    if (post.isRestricted && creatorId.isNotEmpty()) {
        val plans = runCatching { Client.fanboxApi.planListCreator(creatorId).body?.plans }
            .getOrNull().orEmpty()
        if (plans.isNotEmpty()) {
            items += FanboxSectionItem(plansTitle)
            plans.forEach { items += FanboxPlanItem(it) }
        }
    }

    val commentBody = runCatching {
        Client.fanboxApi.postGetComments(postId, limit = 30).body
    }.getOrNull()
    val comments = commentBody?.commentList?.items.orEmpty()
    if (comments.isNotEmpty()) {
        items += FanboxSectionItem(commentsTitle)
        comments.forEach { c ->
            items += FanboxCommentItem(c, depth = 0)
            c.replies.orEmpty().forEach { reply -> items += FanboxCommentItem(reply, depth = 1) }
        }
    } else if (post.commentCount > 0 && commentBody?.viewMode == VIEW_MODE_PLEDGE_INSUFFICIENT) {
        // 头部已经把评论数亮出来了,这里再静默少一整段会被当成加载失败 —— 明说是赞助门槛。
        // 注意 viewMode 说的是「你还能做什么」而不是「能不能看」:免费帖返回的是
        // LOGIN_REQUIRED(未登录不能发评论)但 items 照给,所以别拿 viewMode != null 当锁判据,
        // 真正的锁信号是 commentList 为 null + PLEDGE_INSUFFICIENT。
        items += FanboxSectionItem(commentsTitle)
        items += FanboxNoticeItem(commentsLocked)
    }
    return FeedPage(items, null)
}

/**
 * 正文 → 列表项。块的形状按 `type` 分家(schema 见 [ceui.loxia.FanboxPostBody]):
 * `article` 是块数组 + 资源 map,其余几种是扁的 text/images/files。
 *
 * 拿不到 body(受限 / 被挡)返回空表,由调用方决定挂什么兜底。
 */
private fun bodyItemsOf(post: FanboxPost): List<FeedItem> {
    val body = post.body ?: return emptyList()
    val items = mutableListOf<FeedItem>()
    if (post.type == POST_TYPE_ARTICLE) {
        body.blocks.orEmpty().forEachIndexed { index, block ->
            when (block.type) {
                // 空段落是编辑器里的空行,列表本身有间距,再插一个空 cell 是双倍空白。
                BLOCK_PARAGRAPH -> block.text?.takeIf { it.isNotBlank() }?.let {
                    items += FanboxBodyTextItem(
                        index, it, false, block.links.orEmpty(), block.styles.orEmpty()
                    )
                }
                BLOCK_HEADER -> block.text?.takeIf { it.isNotBlank() }?.let {
                    items += FanboxBodyTextItem(index, it, true, emptyList())
                }
                BLOCK_IMAGE -> body.imageMap?.get(block.imageId)?.let {
                    items += FanboxBodyImageItem(index, it)
                }
                BLOCK_FILE -> body.fileMap?.get(block.fileId)?.let {
                    items += fileItem(index, it)
                }
                BLOCK_URL_EMBED -> body.urlEmbedMap?.get(block.urlEmbedId)?.let {
                    items += urlEmbedItem(index, it)
                }
                BLOCK_EMBED -> body.embedMap?.get(block.embedId)?.let {
                    items += embedItem(index, it)
                }
            }
        }
        return items
    }

    // article 以外的几种:正文是一段纯文本 + 一串资源。entry 给的是整段 HTML,
    // 没有结构可拆,原样按文本渲染(标签会露出来,但比一片空白强)。
    val text = body.text ?: body.html
    text?.takeIf { it.isNotBlank() }?.let {
        items += FanboxBodyTextItem(0, it, false, emptyList())
    }
    body.images.orEmpty().forEachIndexed { index, image ->
        items += FanboxBodyImageItem(index, image)
    }
    body.files.orEmpty().forEachIndexed { index, file ->
        items += fileItem(index, file)
    }
    return items
}

private fun fileItem(index: Int, file: FanboxFile): FanboxBodyLinkItem = FanboxBodyLinkItem(
    key = "file-$index-${file.id.orEmpty()}",
    title = file.name.orEmpty().ifEmpty { file.url.orEmpty() },
    subtitle = formatFileSize(file.size),
    url = file.url.orEmpty(),
)

/** `default` 之外的几种嵌入(站内帖子、HTML 卡片)没有可点的 url,只留标题。 */
private fun urlEmbedItem(index: Int, embed: FanboxUrlEmbed): FanboxBodyLinkItem {
    val url = embed.url.orEmpty()
    return FanboxBodyLinkItem(
        key = "embed-$index-${embed.id.orEmpty()}",
        title = embed.host.orEmpty().ifEmpty { url.ifEmpty { embed.type.orEmpty() } },
        subtitle = url,
        url = url,
    )
}

/** 旧式嵌入只给服务商 + 内容 id,链接得自己拼;不认识的服务商就只展示不给点。 */
private fun embedItem(index: Int, embed: FanboxEmbed): FanboxBodyLinkItem {
    val provider = embed.serviceProvider.orEmpty()
    val contentId = embed.contentId.orEmpty()
    val url = when (provider) {
        "twitter" -> "https://twitter.com/i/status/$contentId"
        "youtube" -> "https://www.youtube.com/watch?v=$contentId"
        "vimeo" -> "https://vimeo.com/$contentId"
        "soundcloud" -> "https://soundcloud.com/$contentId"
        "fanbox" -> "https://www.fanbox.cc/@$contentId"
        else -> ""
    }
    return FanboxBodyLinkItem(
        key = "old-embed-$index-${embed.id.orEmpty()}",
        title = provider.ifEmpty { contentId },
        subtitle = url.ifEmpty { contentId },
        url = url,
    )
}

private fun formatFileSize(size: Long): String = when {
    size <= 0L -> ""
    size >= 1024L * 1024L -> String.format("%.1f MB", size / 1024.0 / 1024.0)
    size >= 1024L -> String.format("%.1f KB", size / 1024.0)
    else -> "$size B"
}

private const val STYLE_BOLD = "bold"
private const val POST_TYPE_ARTICLE = "article"
private const val BLOCK_PARAGRAPH = "p"
private const val BLOCK_HEADER = "header"
private const val BLOCK_IMAGE = "image"
private const val BLOCK_FILE = "file"
private const val BLOCK_EMBED = "embed"
private const val BLOCK_URL_EMBED = "url_embed"

/** 赞助档位不够,评论区整个不下发(`commentList` 为 null)。 */
private const val VIEW_MODE_PLEDGE_INSUFFICIENT = "PLEDGE_INSUFFICIENT"

data class FanboxDetailHeaderItem(
    val post: FanboxPost,
    val showWebNotice: Boolean,
) : FeedItem {
    override val feedKey: Any get() = "header-${post.id}"
}

/** 块自己没有 id,拿数组下标当 key —— 同一页里唯一就够了。 */
data class FanboxBodyTextItem(
    val index: Int,
    val text: String,
    val isHeader: Boolean,
    val links: List<FanboxBlockLink>,
    val styles: List<FanboxBlockStyle> = emptyList(),
) : FeedItem {
    override val feedKey: Any get() = "body-text-$index"
}

data class FanboxBodyImageItem(val index: Int, val image: FanboxImage) : FeedItem {
    override val feedKey: Any get() = "body-image-$index-${image.id.orEmpty()}"
}

data class FanboxBodyLinkItem(
    val key: String,
    val title: String,
    val subtitle: String,
    val url: String,
) : FeedItem {
    override val feedKey: Any get() = "body-link-$key"
}

data class FanboxSectionItem(val title: String) : FeedItem {
    override val feedKey: Any get() = "section-$title"
}

data class FanboxNoticeItem(val text: String) : FeedItem {
    override val feedKey: Any get() = "notice-$text"
}

data class FanboxPlanItem(val plan: FanboxPlan) : FeedItem {
    override val feedKey: Any get() = "plan-${plan.id}"
}

data class FanboxCommentItem(val comment: FanboxComment, val depth: Int) : FeedItem {
    override val feedKey: Any get() = "comment-${comment.id}"
}
