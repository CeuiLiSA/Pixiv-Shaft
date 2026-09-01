package ceui.pixiv.ui.rank

import android.os.Bundle
import ceui.loxia.Novel
import ceui.pixiv.api.Client
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.feeds.pixiv.pixivFeedSource
import ceui.pixiv.ui.common.NovelFeedFragment
import ceui.pixiv.ui.common.NovelFeedItem

/**
 * 小说排行榜列表页（feeds 框架版，替代 legacy FragmentRankNovel + RankNovelRepo + NAdapter）。
 * 宿主是 RankActivity 的 ViewPager（dataType=「小说」），一个 tab 一个实例；卡片 / 收藏同步 /
 * 骨架图全部复用基类 [NovelFeedFragment] 的主力小说卡（recy_novel）。插画榜的兄弟实现见
 * [RankIllustFeedFragment]（同一个 RankActivity）。
 *
 * 与 legacy 的行为对齐点：
 * - 端点：`/v1/novel/ranking`，[NOVEL_MODES] 与 legacy FragmentRankNovel.API_TITLES_VALUES
 *   逐字对齐；[queryDate] 空串 = 最新一期（legacy 也是把空串直接塞进 query，这里改成不带该参数）；
 * - 懒加载：Fragment 会被 ViewPager 提前创建，数据等 tab 真正可见（onResume）才拉，
 *   不替用户偷偷请求没打开过的 tab（R18 榜尤其）。legacy 靠 BaseLazyFragment.setUserVisibleHint
 *   吃 pager 的 BEHAVIOR_SET_USER_VISIBLE_HINT 默认档，feeds 版改吃 onResume——
 *   **宿主 pager 必须换成 BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT**，否则懒加载失效；
 * - 过滤：走 [NovelFeedItem.of] 的通用链（tag / id / 作者 / R18），与 legacy 默认
 *   [ceui.lisa.core.Mapper] 的小说分支逐条一致。
 *
 * **R18 榜例外**（这一条刻意不对齐 legacy，因为 legacy 那边是个 bug）：`day_r18` 榜跳过全局
 * R18 过滤。legacy RankNovelRepo 没覆写 mapper，于是用户一开 R18 屏蔽，这个名字就叫「R」的 tab
 * 恒空 —— 而插画侧的 RankIllustRepo 当年专门覆写 mapper 调 enableSkipR18Filter 处理了同一件事
 *（feeds 版 [ceui.pixiv.ui.rank.RankIllustFeedFragment] 原样保住）。R18 专属端点本身就是用来看
 * R18 的，两边不该各行其是。
 *
 * 与插画榜刻意不同的两点（都是「legacy 小说榜本来就没有」，不在迁移里顺手加）：
 * - 不喂 DiscoveryPool：那是插画画像池，legacy RankNovelRepo 没有 doOnNext，小说也不是它的口味；
 * - 不开首屏磁盘缓存：legacy 无缓存，且小说榜是冷路径，而 6 个 mode 与插画榜的
 *   day/week/day_male/... 重名——真要开必须用独立 slot 前缀（"rank-novel-$mode"），
 *   否则会和 `rank-$mode` 的 IllustResponse 快照撞槽；再叠上 FeedFirstPageCache 有限的
 *   LRU 槽位（MAX_CACHED_SLOTS=24，插画榜自己就能吃掉 19 个），得不偿失。
 */
class RankNovelFeedFragment : NovelFeedFragment() {

    private val mode: String by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(ARG_MODE).orEmpty()
    }

    /** 空/null = 最新一期（日期选择器没选过）。 */
    private val queryDate: String? by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(ARG_DATE)?.takeIf { it.isNotEmpty() }
    }

    /** R18 专属榜端点，别用全局 R18 过滤把它清空（对齐 RankIllustFeedFragment 的同名判定）。 */
    private val skipR18Filter: Boolean get() = mode.contains("r18")

    override val feedViewModel by feedViewModels(autoLoad = false) {
        // 零捕获约定（见 feedViewModels 文档）：source/mapper 归 VM 长期持有，
        // 只捕获局部值、映射走伴生函数，不把 Fragment 实例钉进 VM
        val mode = mode
        val queryDate = queryDate
        val skipR18Filter = skipR18Filter
        pixivFeedSource({ Client.appApi.getRankingNovels(mode, queryDate) }) { resp, _ ->
            mapRankNovelPage(resp.displayList, skipR18Filter)
        }
    }

    companion object {
        private const val ARG_MODE = "rank_novel_mode"
        private const val ARG_DATE = "rank_novel_date"

        /**
         * 与 RankActivity 的小说 tab 标题一一对应（顺序即 tab 顺序：日榜 / 每周 / 男性向 /
         * 女性向 / 新人 / R），逐字对齐 legacy FragmentRankNovel.API_TITLES_VALUES。
         */
        @JvmField
        val NOVEL_MODES = arrayOf(
            "day", "week", "day_male", "day_female", "week_rookie", "day_r18",
        )

        @JvmStatic
        fun newInstance(mode: String, date: String?): RankNovelFeedFragment {
            return RankNovelFeedFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODE, mode)
                    putString(ARG_DATE, date)
                }
            }
        }

        /** 页响应 → 条目。跑在 Default 线程、被 VM 长期持有，放伴生对象保证零捕获。 */
        private fun mapRankNovelPage(
            novels: List<Novel>,
            skipR18Filter: Boolean,
        ): List<FeedItem> {
            return novels.mapNotNull { NovelFeedItem.of(it, skipR18Filter = skipR18Filter) }
        }
    }
}
