package ceui.pixiv.ui.newworks

import ceui.pixiv.api.Client
import ceui.loxia.Novel
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.pixiv.cachedPixivFeedSource
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.NovelFeedFragment
import ceui.pixiv.ui.common.NovelFeedItem

/**
 * 最新作品「小说」tab（feeds 框架版，替代 legacy FragmentLatestNovel + NAdapter）。
 * 宿主是 [ceui.lisa.fragments.FragmentNew] 的 ViewPager；卡片/收藏/跳转全部复用基类
 * [NovelFeedFragment] 的主力小说卡（recy_novel）。
 *
 * 懒加载 + 本地优先，同插画 tab。小说没有 DiscoveryPool 采集（对齐 legacy Mapper 小说分支
 * 不喂画像池），mapper 只做内容过滤。
 */
class LatestNovelFeedFragment : NovelFeedFragment() {

    override val feedViewModel by feedViewModels(autoLoad = false) {
        cachedPixivFeedSource(
            slot = "latest-novel",
            initialFetch = { Client.appApi.getNewNovels() },
        ) { resp, _ ->
            mapLatestNovels(resp.displayList)
        }
    }

    companion object {
        /** 页响应 → 条目。跑在 Default 线程、被 VM 长期持有，放伴生对象保证零捕获。 */
        private fun mapLatestNovels(novels: List<Novel>): List<FeedItem> {
            return novels.mapNotNull { NovelFeedItem.of(it) }
        }
    }
}
