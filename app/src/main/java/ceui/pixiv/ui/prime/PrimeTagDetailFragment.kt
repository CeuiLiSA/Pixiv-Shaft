package ceui.pixiv.ui.prime

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import ceui.lisa.R
import ceui.loxia.Illust
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.loxia.Client
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.IllustFeedFragment
import ceui.pixiv.ui.common.IllustFeedItem
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding

/**
 * 单个 Prime 标签的精选插画（feeds 框架版）。
 *
 * 数据来自 pixshaft-api 的 `/v1/prime/tags/{key}/illusts`，每页 [PAGE_SIZE] 条，游标就是
 * 下一页的 offset。以前这份榜单是 APK 里的 assets（183MB 原始 / 安装包约 19MB），点一个
 * 标签就一次性读整包 300 条进内存；现在数据在服务端，翻到哪拉到哪。
 *
 * 不接 [FeedSource.loadFromCache]：这是个用完即走的二级页面，没必要为它落一份磁盘快照。
 *
 * 点击/收藏/长按菜单全部继承自 [IllustFeedFragment]：比旧版（单独发请求打开一张不带滑动的
 * 详情）多了同列表内的滑动翻页。
 */
class PrimeTagDetailFragment : IllustFeedFragment(R.layout.fragment_toolbar_feed) {

    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)

    /**
     * 游标是**下一页的 offset 的字符串形式**：[IllustFeedFragment] 的契约是
     * `FeedViewModel<String>`（pixiv 那套 feed 的游标是 nextUrl），本页的游标天然是个整数，
     * 借这层壳带过去；null = 已到底。
     */
    override val feedViewModel by feedViewModels<String> {
        val key = requireArguments().getString(ARG_KEY).orEmpty()
        FeedSource { cursor ->
            // Retrofit suspend 自带 main-safe；解析量也只剩一页 30 条，不用再自己切线程。
            val page = Client.pixshaft.primeTagIllusts(key, cursor?.toIntOrNull() ?: 0, PAGE_SIZE)
            FeedPage(
                page.illusts.mapNotNull { illust -> IllustFeedItem.of(illust) },
                page.next_offset?.toString(),
            )
        }
    }

    /**
     * **不把本页的 bean 合进 ObjectPool / 全局关注态**（同 [ceui.pixiv.ui.watchlater.WatchLaterFeedFragment]
     * 的规则）。
     *
     * 基类默认会把列表 bean 喂给 [ceui.pixiv.ui.common.IllustFeedPoolSync]，因为别的 feed 页拿的
     * 都是刚下行的新鲜数据。本页拿的是 **策展快照冻结那一刻的 JSON** —— 搬到服务端之后它依然
     * 是快照，不会跟着 pixiv 更新。喂进去就是拿旧值盖新值：`ObjectPool.mergeKeepingExisting` 只把
     * null/空串/空数组当「空」，`is_bookmarked=false`、`total_bookmarks=15835` 都是正经 JSON 原始值，
     * 照盖不误 → 用户已收藏的作品在详情页显示成未收藏；`AppLevelStateHelper.fill` 对 Illust
     * 传的是默认 UpdateMethod（不像它自己的历史分支那样用 IF_ABSENT），旧的 is_followed=false
     * 会把用户这次会话里刚点的「已关注」打回。
     *
     * 关掉不影响从本页点进详情：VActivity 只在池里 miss 时才用 PageData 的 bean 填池。
     */
    override fun poolableBeansOf(item: FeedItem): List<Illust> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding, feedBinding.feedListView)
        binding.toolbarTitle.text = requireArguments().getString(ARG_NAME)
    }

    companion object {
        /** 服务端同样把 limit 卡在 30，两边一起改才有意义。 */
        private const val PAGE_SIZE = 30
        private const val ARG_NAME = "name"
        private const val ARG_KEY = "key"

        fun newInstance(name: String, key: String): PrimeTagDetailFragment {
            return PrimeTagDetailFragment().apply {
                arguments = bundleOf(ARG_NAME to name, ARG_KEY to key)
            }
        }
    }
}
