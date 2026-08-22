package ceui.pixiv.ui.newworks

import android.os.Bundle
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.pixiv.db.discovery.DiscoveryPool
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedLoadPhase
import ceui.pixiv.feeds.pixiv.cachedPixivFeedSource
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.IllustFeedFragment
import ceui.pixiv.ui.common.IllustFeedItem

/**
 * 最新作品「插画」/「漫画」tab（feeds 框架版，替代 legacy FragmentLatestWorks + IAdapter）。
 * 宿主是 [ceui.lisa.fragments.FragmentNew] 的 ViewPager，一个 content_type 一个实例；
 * 卡片、收藏、详情续拉全部复用基类 [IllustFeedFragment] 的标准瀑布流插画卡。
 *
 * 与 legacy 的行为对齐点：
 * - 懒加载（[feedViewModels] autoLoad=false）：Fragment 被 ViewPager 提前创建，数据等
 *   tab 真正可见（onResume）才拉，不替用户偷偷请求没打开过的 tab；
 * - 每页数据在过滤前整页喂 DiscoveryPool（发现页画像采集，对齐 LatestIllustRepo.doOnNext）。
 *
 * 本地优先：给稳定 slot 开磁盘缓存，冷启秒显上次首屏再拉最新覆盖（同 RankIllustFeedFragment）。
 */
class LatestIllustFeedFragment : IllustFeedFragment() {

    private val contentType: String by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(ARG_CONTENT_TYPE) ?: TYPE_ILLUST
    }

    override val feedViewModel by feedViewModels(autoLoad = false) {
        // 零捕获约定（见 feedViewModels 文档）：source/mapper 归 VM 长期持有，
        // 只捕获局部值、映射走伴生函数，不把 Fragment 实例钉进 VM
        val contentType = contentType
        cachedPixivFeedSource(
            slot = "latest-$contentType",
            initialFetch = { Client.appApi.getNewIllusts(contentType) },
        ) { resp, phase ->
            mapLatestPage(resp.displayList, phase, contentType)
        }
    }

    companion object {
        private const val ARG_CONTENT_TYPE = "latest_content_type"

        /** content_type 是接口字面量（"illust"/"manga"），不是展示文案，别本地化。 */
        const val TYPE_ILLUST = "illust"
        const val TYPE_MANGA = "manga"

        @JvmStatic
        fun newInstance(contentType: String): LatestIllustFeedFragment {
            return LatestIllustFeedFragment().apply {
                arguments = Bundle().apply { putString(ARG_CONTENT_TYPE, contentType) }
            }
        }

        /**
         * 页响应 → 条目。跑在 Default 线程、被 VM 长期持有，放伴生对象保证零捕获。
         * [phase] 为缓存恢复时只做纯映射，不喂画像池（拿旧数据重放会污染下游）。
         */
        private fun mapLatestPage(
            illusts: List<Illust>,
            phase: FeedLoadPhase,
            contentType: String,
        ): List<FeedItem> {
            if (phase.isFreshFetch) {
                DiscoveryPool.collect(
                    illusts,
                    if (phase.isFirstPage) "latest:$contentType" else "latest_next:$contentType",
                )
            }
            return illusts.mapNotNull { IllustFeedItem.of(it) }
        }
    }
}
