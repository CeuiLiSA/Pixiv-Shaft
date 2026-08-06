package ceui.pixiv.ui.fanbox

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentPagerAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.CellFanboxCreatorBinding
import ceui.lisa.databinding.CellFanboxPostBinding
import ceui.lisa.databinding.ViewpagerWithTablayoutBinding
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.lisa.view.LinearItemDecoration
import ceui.loxia.Client
import ceui.loxia.FanboxCreator
import ceui.loxia.FanboxHeaderInterceptor
import ceui.loxia.FanboxPost
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.ViewPagerFragment
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.clearGlideOnRecycle
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide

/**
 * pixiv FANBOX 首页(原生)。两个 tab 一一对应网页版首页的两个接口:
 * 「投稿」= `post.listHome`(需登录,带 nextUrl 翻页),「推荐创作者」= `creator.listRecommended`
 * (单页,无游标)。
 *
 * 认证是 cookie 制,靠用户先在 FANBOX 网页里登录一次 —— 缺 FANBOXSESSID 时侧边栏那个入口
 * 会直接把人送去网页版(见 MainActivity),不会进到这里来看一屏 401。
 *
 * 帖子正文拿不到(post.info 恒 403、post.get 不含 body),所以点卡片一律落回网页。
 */
class FanboxHomeFragment : Fragment(R.layout.viewpager_with_tablayout), ViewPagerFragment {

    private val binding by viewBinding(ViewpagerWithTablayoutBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.title = " "
        binding.toolbarTitle.text = getString(R.string.fanbox_entry)
        binding.toolbar.setNavigationOnClickListener { activity?.finish() }

        val tabs = listOf(
            getString(R.string.fanbox_tab_posts) to ::FanboxPostFeedFragment,
            getString(R.string.fanbox_tab_creators) to ::FanboxCreatorFeedFragment,
        )
        val fragments: List<Fragment> = tabs.map { it.second() }

        binding.viewPager.adapter = object : FragmentPagerAdapter(
            childFragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT
        ) {
            override fun getItem(position: Int): Fragment = fragments[position]
            override fun getCount(): Int = tabs.size
            override fun getPageTitle(position: Int): CharSequence = tabs[position].first
        }
        binding.tabLayout.setupWithViewPager(binding.viewPager)
    }
}

/** 「投稿」tab:post.listHome,服务端用 body.nextUrl 给绝对 URL 翻页。 */
class FanboxPostFeedFragment : FeedFragment() {

    override val feedViewModel by feedViewModels<String> {
        FeedSource { cursor ->
            val resp = if (cursor == null) {
                Client.fanboxApi.postListHome(limit = PAGE_SIZE)
            } else {
                Client.fanboxApi.postListHomeByUrl(cursor)
            }
            val body = resp.body
            FeedPage(
                body?.items.orEmpty().map { FanboxPostItem(it) },
                body?.nextUrl?.takeIf { it.isNotEmpty() },
            )
        }
    }

    override fun onListReady(listView: RecyclerView) {
        listView.addItemDecoration(LinearItemDecoration(16.ppppx))
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(postRenderer())
    }

    private fun postRenderer() = feedRenderer<FanboxPostItem, CellFanboxPostBinding>(
        inflate = CellFanboxPostBinding::inflate,
        create = { cell ->
            // bg_v3_card 是 shape 背景,不裁子 View —— 不开 clipToOutline 封面会顶出圆角。
            cell.binding.root.clipToOutline = true
            cell.binding.root.setOnClick { openPost(cell.item.post) }
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
        val excerpt = post.excerpt.orEmpty()
        cell.binding.excerpt.isVisible = excerpt.isNotEmpty()
        cell.binding.excerpt.text = excerpt
        val tags = post.tags.orEmpty().filter { it.isNotEmpty() }
        cell.binding.tags.isVisible = tags.isNotEmpty()
        cell.binding.tags.text = tags.joinToString(" ") { "#$it" }
        cell.binding.likeCount.text = post.likeCount.toString()
        cell.binding.commentCount.text = post.commentCount.toString()
        cell.binding.badgeR18.isVisible = post.hasAdultContent
        // cover 可能整个是 null(实测有),不收起来会留一大片空白。
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

    private fun openPost(post: FanboxPost) {
        val creatorId = post.creatorId.orEmpty()
        openFanboxWeb(
            if (creatorId.isEmpty()) "https://www.fanbox.cc/"
            else "https://www.fanbox.cc/@$creatorId/posts/${post.id}"
        )
    }

    companion object {
        private const val PAGE_SIZE = 10
    }
}

/** 「推荐创作者」tab:creator.listRecommended。响应没有翻页游标,就是单页。 */
class FanboxCreatorFeedFragment : FeedFragment() {

    override val feedViewModel by feedViewModels<String> {
        FeedSource { _ ->
            val resp = Client.fanboxApi.creatorListRecommended(limit = PAGE_SIZE)
            FeedPage(resp.body?.creators.orEmpty().map { FanboxCreatorItem(it) }, null)
        }
    }

    override fun onListReady(listView: RecyclerView) {
        listView.addItemDecoration(LinearItemDecoration(16.ppppx))
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(creatorRenderer())
    }

    private fun creatorRenderer() = feedRenderer<FanboxCreatorItem, CellFanboxCreatorBinding>(
        inflate = CellFanboxCreatorBinding::inflate,
        create = { cell ->
            cell.binding.root.clipToOutline = true
            cell.binding.root.setOnClick { openCreator(cell.item.creator) }
        },
        recycle = { cell ->
            cell.binding.creatorIcon.clearGlideOnRecycle()
            previewsOf(cell.binding).forEach { it.clearGlideOnRecycle() }
        },
    ) { cell ->
        val creator = cell.item.creator
        cell.binding.creatorName.text = creator.user?.name.orEmpty()
        val category = creator.category.orEmpty()
        cell.binding.category.isVisible = category.isNotEmpty()
        cell.binding.category.text = category
        val desc = creator.description.orEmpty().replace('\r', ' ').replace('\n', ' ').trim()
        cell.binding.description.isVisible = desc.isNotEmpty()
        cell.binding.description.text = desc
        val badge = when {
            creator.isSupported -> getString(R.string.fanbox_supported)
            creator.isFollowed -> getString(R.string.fanbox_followed)
            else -> null
        }
        cell.binding.relationBadge.isVisible = badge != null
        badge?.let { cell.binding.relationBadge.text = it }
        cell.binding.badgeR18.isVisible = creator.hasAdultContent

        Glide.with(cell.binding.creatorIcon)
            .load(GlideUtil.getUrl(creator.user?.iconUrl))
            .placeholder(R.color.v3_surface_2)
            .into(cell.binding.creatorIcon)

        // profileItems 常常不足 4 张(甚至一张没有),缺的格子收掉,别留一排空色块。
        val items = creator.profileItems.orEmpty()
        val views = previewsOf(cell.binding)
        views.forEachIndexed { index, imageView ->
            val item = items.getOrNull(index)
            imageView.isVisible = item != null
            if (item != null) {
                Glide.with(imageView)
                    .load(GlideUtil.getUrl(item.thumbnailUrl ?: item.imageUrl))
                    .placeholder(R.color.v3_surface_2)
                    .into(imageView)
            }
        }
        cell.binding.previewRow.isVisible = items.isNotEmpty()
    }

    private fun openCreator(creator: FanboxCreator) {
        val creatorId = creator.creatorId.orEmpty()
        openFanboxWeb(
            if (creatorId.isEmpty()) "https://www.fanbox.cc/" else "https://www.fanbox.cc/@$creatorId"
        )
    }

    companion object {
        private const val PAGE_SIZE = 20

        private fun previewsOf(binding: CellFanboxCreatorBinding): List<ImageView> = listOf(
            binding.preview0, binding.preview1, binding.preview2, binding.preview3,
        )
    }
}

data class FanboxPostItem(val post: FanboxPost) : FeedItem {
    override val feedKey: Any get() = post.id
}

/** creatorId 可能为空(服务端偶发),退回 userId 兜底,别让多条塌成同一个身份被 dedup 掉。 */
data class FanboxCreatorItem(val creator: FanboxCreator) : FeedItem {
    override val feedKey: Any
        get() = creator.creatorId?.takeIf { it.isNotEmpty() }
            ?: creator.user?.userId.orEmpty()
}

/**
 * `2026-08-03T14:06:39+09:00` → `2026-08-03 14:06`。
 * 只做字符串裁剪:服务端固定给 ISO8601,不值得为了两行展示引一套时区解析。
 */
internal fun formatFanboxTime(raw: String?): String {
    val s = raw.orEmpty()
    if (s.length < 16) return s
    return s.substring(0, 10) + " " + s.substring(11, 16)
}

/** FANBOX 的详情/创作者页都没有可用的原生接口,一律落回网页版。 */
internal fun Fragment.openFanboxWeb(url: String) {
    startActivity(Intent(requireContext(), TemplateActivity::class.java).apply {
        putExtra(TemplateActivity.EXTRA_FRAGMENT, "网页链接")
        putExtra(Params.URL, url)
        putExtra(Params.TITLE, getString(R.string.fanbox_entry))
        putExtra(Params.PREFER_PRESERVE, true)
    })
}

/** 侧边栏用:没登录过 FANBOX 就别进原生页。 */
internal fun hasFanboxSession(): Boolean = FanboxHeaderInterceptor.hasSession()
