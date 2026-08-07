package ceui.pixiv.ui.fanbox

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.databinding.CellFanboxCommentBinding
import ceui.lisa.databinding.CellFanboxDetailHeaderBinding
import ceui.lisa.databinding.CellFanboxNoticeBinding
import ceui.lisa.databinding.CellFanboxPlanBinding
import ceui.lisa.databinding.CellFanboxSectionBinding
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.lisa.utils.GlideUtil
import ceui.loxia.Client
import ceui.loxia.FanboxComment
import ceui.loxia.FanboxPlan
import ceui.loxia.FanboxPost
import ceui.pixiv.feeds.FeedFragment
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
 * 网页版详情页上能拿到的东西这里都做了:帖子元数据(post.get)、赞助方案(plan.listCreator,
 * 就是付费墙那个「方案列表」背后的数据)、完整评论区(post.getComments,含楼中楼)。
 *
 * **唯独正文拿不到**:`post.info` 恒 403 —— 不是权限也不是请求姿势问题,同一套 cookie/头下
 * `post.listHome` 稳定 200 而 `post.info` 稳定 403,连网页端自己在 WebView 里调都吃 CORS 错误
 * (服务端返错误码时不带 ACAO),帖子页渲染出来是一块骨架屏。`post.get` 的 post 对象没有 body
 * 字段,两个列表接口对可读帖也给 null。所以正文位置放的是「在网页中打开」,不做假装能显示的空壳。
 */
class FanboxPostDetailFragment : FeedFragment(R.layout.fragment_toolbar_feed) {

    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)

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
        FeedSource { _ -> loadFanboxPostDetail(id, plansTitle, commentsTitle, commentsLocked) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding, feedBinding.feedListView)
        binding.toolbarTitle.text = getString(R.string.fanbox_entry)
    }

    override fun onListReady(listView: RecyclerView) {
        listView.addItemDecoration(LinearItemDecoration(12.ppppx))
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(
            headerRenderer(),
            sectionRenderer(),
            noticeRenderer(),
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
            val excerpt = post.excerpt.orEmpty()
            cell.binding.excerpt.isVisible = excerpt.isNotEmpty()
            cell.binding.excerpt.text = excerpt
            val tags = post.tags.orEmpty().filter { it.isNotEmpty() }
            cell.binding.tags.isVisible = tags.isNotEmpty()
            cell.binding.tags.text = tags.joinToString(" ") { "#$it" }
            cell.binding.likeCount.text = post.likeCount.toString()
            cell.binding.commentCount.text = post.commentCount.toString()
            val coverUrl = post.cover?.url.orEmpty()
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
 * 拼一页详情:元数据 → (受限时)赞助方案 → 评论。三个请求都可能各自失败,
 * 但只有元数据是必须的 —— 方案/评论拿不到就少一段,不该让整页塌掉。
 */
private suspend fun loadFanboxPostDetail(
    postId: String,
    plansTitle: String,
    commentsTitle: String,
    commentsLocked: String,
): FeedPage<String> {
    val post = Client.fanboxApi.postGet(postId).body?.post
        ?: return FeedPage(emptyList(), null)

    val items = mutableListOf<FeedItem>(FanboxDetailHeaderItem(post))

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

/** 赞助档位不够,评论区整个不下发(`commentList` 为 null)。 */
private const val VIEW_MODE_PLEDGE_INSUFFICIENT = "PLEDGE_INSUFFICIENT"

data class FanboxDetailHeaderItem(val post: FanboxPost) : FeedItem {
    override val feedKey: Any get() = "header-${post.id}"
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
