package ceui.pixiv.ui.user

import android.content.Intent
import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.loxia.Illust
import ceui.loxia.ImageUrls
import ceui.loxia.Tag
import ceui.loxia.User
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.UserTagIllust
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.IllustFeedFragment
import ceui.pixiv.ui.common.IllustFeedItem
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * issue #569：某画师「按 Tag 筛选」后的插画/漫画作品列表（feeds 框架版，替代 legacy
 * FragmentUserIllustByTag + UserIllustTagRepo + IAdapter）。
 *
 * 数据走网页 ajax /ajax/user/{id}/{category}/tag（app-api 无此能力），offset 翻页；category 取
 * `illusts` / `manga`（issue #996：两端点响应同构，共用本页，按 CONTENT_TYPE 参数分流）。
 * 把精简的网页 work 对象映射成 Illust 复用标准瀑布流插画卡。列表项点进详情 / 下载时该精简
 * bean 缺分页图 / 原图，由详情页与下载链路的 isFullDetail 守卫回 v1/illust/detail 补全
 *（见 ceui.loxia.fetchFullIllustDetail）。
 */
class UserIllustByTagFeedFragment : IllustFeedFragment(R.layout.fragment_toolbar_feed) {

    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)

    private val userId: Long by lazy(LazyThreadSafetyMode.NONE) {
        Params.getUserId(requireArguments())
    }
    // 命名避开 Fragment.getTag()（同 JVM 签名会被判「accidental override」）。
    private val filterTag: String by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(Params.KEY_WORD).orEmpty()
    }
    // 网页端点段："illusts" / "manga"。缺省 illusts,兼容旧入口(未带 CONTENT_TYPE 的老 args)。
    private val category: String by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(Params.CONTENT_TYPE) ?: UserTagSearchSheet.CATEGORY_ILLUSTS
    }

    override val feedViewModel by feedViewModels {
        // 零捕获：source 只吃 Long userId + String tag + String category。
        UserIllustByTagFeedSource(userId, filterTag, category)
    }

    // 游标是网页 offset（"48"），不是 app-api illust nextUrl；base KDoc 要求本地/非 URL 源覆写成
    // null，否则详情页 pager 把它当 @Url 请求 getNextIllust("48") → 404。
    override val detailContinuationCursor: String? get() = null

    // 网页 ajax 的精简 work 没有 is_bookmarked / total_bookmarks / is_followed 字段，
    // toIllustsBean 出来全是 primitive 默认值 false/0。喂池会把当前用户刚点的收藏/关注态
    // 盖回假值（mergeKeepingExisting 不把 false/0 当空值）——本页入口就在画师主页的标签
    // 筛选条，刚关注完点进来立刻复现。详情页 isFullDetail 守卫会回拉全量，不缺这份。
    override fun poolableBeansOf(item: FeedItem): List<Illust> = emptyList()

    /**
     * 这一页有个结构性错位：筛选条上的 tag 是 [ceui.lisa.activities.UserV3WorkTabFragment]
     * 从 **app-api**（已登录视角）首屏作品本地聚合出来的，而筛选本身走 **www.pixiv.net 的 ajax**。
     * 没同步网页 cookie 时后者是匿名身份，看不到敏感作品，于是「chip 明明在，点进去 0 件」。
     *
     * 已实证（画师 86104346）：app-api 视角 70 件、匿名网页只见 51 件，差的 19 件里
     * `x_restrict > 0` 的只有 2 件，其余全是 `sanity_level` 4/6 的敏感作品。tag「ケイ」名下
     * 唯一那件正在其中，所以匿名恒 0；同步网页 cookie 后立刻筛出 1 件。
     * 主因是 sanity_level 而非 R-18，文案别写成「R-18」误导。
     */
    override val emptyStateText: CharSequence
        get() = if (SessionManager.hasWebCookie) {
            super.emptyStateText
        } else {
            getString(R.string.user_tag_filter_empty_need_web_login)
        }

    /** 缺网页会话时直接把「去登录」摆在空态上——原因写得再清楚，也不该让用户自己去侧边栏翻入口。 */
    override val emptyStateAction: Pair<CharSequence, () -> Unit>?
        get() = if (SessionManager.hasWebCookie) {
            null
        } else {
            getString(R.string.street_web_login_confirm) to {
                startActivity(
                    Intent(requireContext(), TemplateActivity::class.java).apply {
                        putExtra(TemplateActivity.EXTRA_FRAGMENT, "Web首页")
                        putExtra(Params.AUTO_WEB_LOGIN, true)
                    }
                )
            }
        }

    /**
     * 从空态那个「去登录」跳出去登完回来时，页面还停在旧的空结果上——用户刚登完却看见
     * 同一句「需要登录」和同一个按钮。同步到网页会话就自动重拉一次。
     * 只在「走时没有、回来有了」这一档触发，别把普通的切后台回来也变成刷新。
     *
     * null = 还没 pause 过（首次 onResume）。不能用 false 当初值：VM 是 autoLoad 的，首屏在
     * init 里已经发出去了，此时若用户本来就有网页 cookie，`!false && true` 恒成立 —— 一进页面
     * 就把在飞的首屏 cancel 掉重发一次，恰恰对本页唯一能正常工作的那批用户每次都多打一枪。
     */
    private var hadWebCookieOnPause: Boolean? = null

    override fun onPause() {
        super.onPause()
        hadWebCookieOnPause = SessionManager.hasWebCookie
    }

    override fun onResume() {
        super.onResume()
        if (hadWebCookieOnPause == false && SessionManager.hasWebCookie) {
            feedViewModel.refresh()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding, feedBinding.feedListView)
        // 对齐 legacy getToolbarTitle：非空 tag 显示「#tag」，空 tag 退化为空标题。
        binding.toolbarTitle.text = if (filterTag.isEmpty()) "" else "#$filterTag"
    }

    companion object {
        @JvmStatic
        @JvmOverloads
        fun newInstance(
            userId: Long,
            tag: String?,
            category: String = UserTagSearchSheet.CATEGORY_ILLUSTS,
        ): UserIllustByTagFeedFragment {
            return UserIllustByTagFeedFragment().apply {
                arguments = Bundle().apply {
                    putLong(Params.USER_ID, userId)
                    putString(Params.KEY_WORD, tag)
                    putString(Params.CONTENT_TYPE, category)
                }
            }
        }
    }
}

/**
 * 按 Tag 筛选画师插画/漫画的数据源：网页 ajax（[Client.webApi]），offset 翻页。
 * 每页精简 work → Illust → [IllustFeedItem.of]（含全局内容过滤，对齐 legacy 基类 Mapper）。
 * 游标 = 下一页 offset（已加载条数）；works 空或已到 total 则停。零 Fragment 捕获。
 */
class UserIllustByTagFeedSource(
    private val userId: Long,
    private val tag: String,
    private val category: String,
) : FeedSource<String> {

    // 游标就是下一页 offset（编码成 String，对齐 IllustFeedFragment 固定的 String 游标类型）。
    override suspend fun load(cursor: String?): FeedPage<String> {
        val offset = cursor?.toIntOrNull() ?: 0
        val resp = Client.webApi
            .getUserIllustsByTag(userId, category, tag, offset, PAGE_SIZE)
        // 网页 ajax 的业务错误(限流、参数非法、要求登录…)是 HTTP 200 + error:true + body:null。
        // 不认这一层的话 works 空、total 0，会被渲染成「筛出来 0 件」的空白页 —— 用户既看不到
        // 原因也点不到重试。抛出去交给 feeds 的错误态(issue #956)。
        if (resp.error == true) {
            throw RuntimeException(
                resp.message.orEmpty().ifEmpty { "user/illusts/tag failed" }
            )
        }
        val body = resp.body
        val works = body?.works ?: emptyList()
        val total = body?.total ?: 0
        val loaded = offset + works.size
        // gson-free 映射 + 内容过滤挪 Default，保住 load 的 main-safe 契约。
        val items = withContext(Dispatchers.Default) {
            works.mapNotNull { IllustFeedItem.of(it.toIllust(), skipMuteUserFilter = true) }
        }
        val next = if (works.isNotEmpty() && loaded < total) loaded.toString() else null
        return FeedPage(items, next)
    }

    companion object {
        private const val PAGE_SIZE = 48
    }
}

// 网页方图缩略图路径形如 .../img-master/img/<日期>/<id>_pN_square1200.jpg,或画师自定义封面的
// .../custom-thumb/img/<日期>/<id>_pN_custom1200.jpg。两者底下都有同一张 img-master/_master1200,
// 故从日期路径+作品号重建标准尺寸 URL。编译一次复用(每页 48 项,别在 map 里反复 new Regex)。
private val IMG_PATH_REGEX = Regex("/img/(.+?)_(?:square|custom|master)1200\\.\\w+")

/**
 * 网页 work → Illust。务必 visible = true，否则被 [ceui.lisa.core.Mapper] / feeds 内容过滤
 * 当不可见整条过滤掉。图片走同一 i.pximg.net CDN：由方图 url 重建无裁切的 master1200（跟 app-api 同形）。
 */
internal fun UserTagIllust.toIllust(): Illust {
    val square = url ?: ""
    val m = IMG_PATH_REGEX.find(square)
    val imageUrls = if (m != null) {
        val rel = m.groupValues[1] // 2024/11/11/18/36/26/124200157_p0
        val medium = "https://i.pximg.net/c/540x540_70/img-master/img/${rel}_master1200.jpg"
        ImageUrls(
            medium = medium,
            large = "https://i.pximg.net/c/600x1200_90_webp/img-master/img/${rel}_master1200.jpg",
            square_medium = square.ifEmpty { medium },
        )
    } else if (square.isNotEmpty()) {
        ImageUrls(medium = square, large = square, square_medium = square)
    } else {
        ImageUrls()
    }
    // 头像:列表已带,先填上,免得点进详情(回 API 补全前)那一下是空头像占位
    val avatar = profileImageUrl?.takeIf { it.isNotEmpty() }
    return Illust(
        id = id,
        title = title ?: "",
        visible = true,
        width = width,
        height = height,
        page_count = if (pageCount > 0) pageCount else 1,
        x_restrict = xRestrict,
        illust_ai_type = aiType,
        create_date = createDate,
        type = when (illustType) {
            1 -> "manga"
            2 -> "ugoira"
            else -> "illust"
        },
        image_urls = imageUrls,
        user = User(
            id = userId,
            name = userName ?: "",
            profile_image_urls = avatar?.let { ImageUrls(medium = it, px_170x170 = it) },
        ),
        // tags 兜空,避免 tagNames/TagAdapter 等对 null 列表崩
        tags = tags?.map { name -> Tag(name = name) } ?: emptyList(),
    )
}
