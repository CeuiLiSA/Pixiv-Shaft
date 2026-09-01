package ceui.pixiv.ui.recommend

import android.os.Bundle
import android.view.View
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.databinding.RecyTrendingArtistBinding
import ceui.lisa.network.ShaftApiV2
import ceui.lisa.network.ShaftApiV2Client
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.lisa.view.LinearItemDecoration
import ceui.loxia.ImageUrls
import ceui.loxia.User
import ceui.pixiv.actions.PixivActions
import ceui.pixiv.feeds.FeedCell
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.feedLikeSync
import ceui.pixiv.ui.common.openUserActivity
import ceui.pixiv.utils.pinHostGlide
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager

/**
 * 人气画师 —— 打自建 shaft-api-v2 的 trending/users:「本站用户在窗口内关注最多的画师」。
 * 三个固定 tab:今日 / 本周 / 本月,默认本周(服务端也以 week 为默认口径)。单 tab 是
 * [TrendingArtistFeedFragment]。
 *
 * 宿主契约见 [TypeTabsRankFragment](换成 window enum 分 tab)。
 */
class TrendingArtistsFragment : TypeTabsRankFragment() {

    override val titleRes: Int get() = R.string.trending_artists_title

    /** window 值是服务端 enum;顺序即 tab 顺序。 */
    override val types: List<String> get() = WINDOWS

    @StringRes
    override fun tabTitleRes(type: String): Int = windowTitleRes(type)

    /** 默认落在「本周」。 */
    override val defaultPos: Int get() = WINDOWS.indexOf(WINDOW_WEEK)

    override fun createPage(type: String): Fragment = TrendingArtistFeedFragment.newInstance(type)

    companion object {
        const val WINDOW_DAY = "day"
        const val WINDOW_WEEK = "week"
        const val WINDOW_MONTH = "month"
        private val WINDOWS = listOf(WINDOW_DAY, WINDOW_WEEK, WINDOW_MONTH)

        @StringRes
        fun windowTitleRes(window: String): Int = when (window) {
            WINDOW_DAY -> R.string.trending_window_day
            WINDOW_MONTH -> R.string.trending_window_month
            else -> R.string.trending_window_week
        }

        /** 「今日 / 本周 / 本月 N 人关注」副标题的复数资源。 */
        fun followersPluralsRes(window: String): Int = when (window) {
            WINDOW_DAY -> R.plurals.trending_artist_followers_day
            WINDOW_MONTH -> R.plurals.trending_artist_followers_month
            else -> R.plurals.trending_artist_followers_week
        }

        @JvmStatic
        fun newInstance(): TrendingArtistsFragment = TrendingArtistsFragment()
    }
}

/** 关注态局部重绑的 payload 标记(按引用识别)。 */
private val PAYLOAD_FOLLOW = Any()

/**
 * 人气画师榜的**单个 window tab**。trending/users 不带代表作,不能复用 [ceui.pixiv.ui.common.UserFeedFragment]
 * 的三格预览卡(会是三块空灰底),所以这里用纯画师行 recy_trending_artist 自绘:名次 + 头像 + 名字 +
 * 「本周 N 人关注」+ 关注按钮。关注切换(乐观翻态 + 长按私密关注)/ LIKED_USER 广播同步 / 点击进
 * 画师页的语义逐条对齐 UserFeedFragment。
 *
 * `autoLoad = false`:宿主 pager 是 RESUME_ONLY_CURRENT,三个 tab 不要一次三枪(同壁纸榜子页)。
 */
class TrendingArtistFeedFragment : FeedFragment() {

    private val window: String by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(ARG_WINDOW) ?: TrendingArtistsFragment.WINDOW_WEEK
    }

    override val feedViewModel by feedViewModels(autoLoad = false) {
        // 零捕获:只捕获局部值,不把 Fragment 钉进 VM。
        val w = window
        TrendingArtistsFeedSource(window = w)
    }

    private val userGlide: RequestManager by lazy { Glide.with(this) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pinHostGlide(userGlide)
        feedLikeSync<TrendingArtistItem>(
            feedViewModel = feedViewModel,
            action = Params.LIKED_USER,
            idOf = { it.user.id },
            transform = { item, followed -> item.withFollowed(followed) },
        ).bind(requireContext(), viewLifecycleOwner)
    }

    override fun onListReady(listView: RecyclerView) {
        listView.addItemDecoration(LinearItemDecoration(12.ppppx))
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(artistRenderer())
    }

    private fun artistRenderer() = feedRenderer<TrendingArtistItem, RecyTrendingArtistBinding>(
        inflate = RecyTrendingArtistBinding::inflate,
        create = { cell ->
            cell.binding.root.setOnClick { cell.itemOrNull?.user?.id?.let { openUserActivity(it) } }
            cell.binding.postLikeUser.setOnClick { toggleFollow(cell) }
            cell.binding.postLikeUser.setOnLongClickListener {
                privateFollow(cell)
                true
            }
        },
        recycle = { cell -> userGlide.clear(cell.binding.userHead) },
        changePayload = { old, new ->
            // 只有关注态变 → 局部重绑关注按钮,不重跑头像 Glide。
            if (old.withFollowed(new.user.is_followed == true) == new) PAYLOAD_FOLLOW else null
        },
        bindPayloads = { cell, payloads ->
            if (payloads.all { it === PAYLOAD_FOLLOW }) {
                renderFollow(cell.binding, cell.item.user.is_followed == true)
                true
            } else {
                false
            }
        },
    ) { cell -> bindArtist(cell) }

    private fun bindArtist(cell: FeedCell<TrendingArtistItem, RecyTrendingArtistBinding>) {
        val b = cell.binding
        val item = cell.item
        val user = item.user
        b.rankIndex.text = item.rank.toString()
        b.userName.text = user.name ?: ""
        b.followCount.text = resources.getQuantityString(
            TrendingArtistsFragment.followersPluralsRes(window), item.followCount, item.followCount
        )
        userGlide.load(GlideUtil.getUrl(user.profile_image_urls?.medium))
            .error(R.drawable.no_profile).into(b.userHead)
        renderFollow(b, user.is_followed == true)
    }

    private fun renderFollow(b: RecyTrendingArtistBinding, followed: Boolean) {
        b.postLikeUser.text = getString(if (followed) R.string.post_unfollow else R.string.post_follow)
    }

    /** VM 里当前这个用户的最新条目(真源);已被刷新挤掉则 null。 */
    private fun currentItem(userId: Long): TrendingArtistItem? {
        return feedViewModel.uiState.value.items
            .firstOrNull { it is TrendingArtistItem && it.user.id == userId } as? TrendingArtistItem
    }

    private fun toggleFollow(cell: FeedCell<TrendingArtistItem, RecyTrendingArtistBinding>) {
        // 关注态真源是 VM 当前状态,不是 cell.item(adapter 已提交的快照,连点两下会读到旧态)。
        val tapped = cell.itemOrNull?.user ?: return
        val userId = tapped.id
        val user = currentItem(userId)?.user ?: tapped
        val target = user.is_followed != true
        renderFollow(cell.binding, target)
        applyFollow(userId, target)
        // 失败回滚由 PixivActionQueue 带相反值再发一次 LIKED_USER,本页 feedLikeSync 收到即拨回。
        if (target) {
            PixivOperate.postFollowUser(userId, PixivActions.defaultFollowRestrict())
        } else {
            PixivOperate.postUnFollowUser(userId)
        }
    }

    /** 长按 = 私密关注(沿用画师卡的长按语义)。 */
    private fun privateFollow(cell: FeedCell<TrendingArtistItem, RecyTrendingArtistBinding>) {
        val userId = cell.itemOrNull?.user?.id ?: return
        renderFollow(cell.binding, true)
        applyFollow(userId, true)
        PixivOperate.postFollowUser(userId, Params.TYPE_PRIVATE)
    }

    private fun applyFollow(userId: Long, followed: Boolean) {
        feedViewModel.updateItems(TrendingArtistItem::class.java) { item ->
            if (item.user.id == userId) item.withFollowed(followed) else item
        }
    }

    companion object {
        private const val ARG_WINDOW = "trending_window"

        /** [window] 取 [TrendingArtistsFragment.WINDOW_DAY] / WINDOW_WEEK / WINDOW_MONTH。 */
        @JvmStatic
        fun newInstance(window: String): TrendingArtistFeedFragment {
            return TrendingArtistFeedFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_WINDOW, window)
                }
            }
        }
    }
}

/**
 * 人气画师条目:[User](由 trending/users 的 meta 拼出,只有 id / name / account / 头像)+
 * 名次 + 窗口内关注人数。内容相等性看三者(data class),关注乐观切态走 [withFollowed]。
 */
data class TrendingArtistItem(
    val user: User,
    val rank: Int,
    val followCount: Int,
) : FeedItem {

    override val feedKey: Any get() = user.id

    /** 关注态变更:copy 出新实例驱动 DiffUtil 重绑关注按钮。 */
    fun withFollowed(followed: Boolean): TrendingArtistItem {
        if (user.is_followed == followed) return this
        return copy(user = user.copy(is_followed = followed))
    }
}

/**
 * 人气画师数据源:shaft-api-v2 trending/users(首屏 trendingUsers,翻页 trendingUsersByUrl)。
 * 响应不带 pixiv 原始 JSON,直接用 meta 三个字段拼 [User];关注态一律 false 让用户以自己名义关注
 * (榜单不知道当前用户关注了谁,同画师榜)。名次按 offset + 序号连续编号(server 回显 offset)。
 */
class TrendingArtistsFeedSource(
    private val window: String,
    private val limitN: Int = 30,
) : FeedSource<String> {

    override suspend fun load(cursor: String?): FeedPage<String> {
        val resp: ShaftApiV2.TrendingUsersResponse = if (cursor == null) {
            ShaftApiV2Client.service.trendingUsers(window = window, limit = limitN)
        } else {
            ShaftApiV2Client.service.trendingUsersByUrl(cursor)
        }
        val base = resp.offset ?: 0
        val items = resp.items.mapIndexedNotNull { i, item -> mapItem(item, base + i + 1) }
        return FeedPage(items, resp.next_url?.takeIf { it.isNotEmpty() })
    }

    companion object {
        private fun mapItem(item: ShaftApiV2.TrendingUserItem, rank: Int): TrendingArtistItem? {
            // id 为 0 / 没有 meta 的条目没有展示价值,且会和其它坏条目撞 feedKey 被去重折叠,直接跳过。
            if (item.target_id == 0L) return null
            val meta = item.meta ?: return null
            val user = User(
                id = item.target_id,
                name = meta.name,
                account = meta.account,
                profile_image_urls = meta.avatar_url?.let { ImageUrls(medium = it) },
                is_followed = false,
            )
            return TrendingArtistItem(user = user, rank = rank, followCount = item.follow_count)
        }
    }
}
