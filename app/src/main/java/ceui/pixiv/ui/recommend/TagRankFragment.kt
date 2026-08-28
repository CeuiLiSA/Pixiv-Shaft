package ceui.pixiv.ui.recommend

import androidx.fragment.app.Fragment
import ceui.lisa.R

/**
 * 标签专区 — 打自建服务端 shaft-api-v2 的 discover/most-bookmarked?type=&tag=。
 * 顶部 插画 / 漫画 / 小说 三个类型 tab([TypeTabsRankFragment]),每个 tab 一页
 * [TagRankPageFragment]:选择条显示当前标签(翻译优先 + 作品数),点它弹 [RankPickerSheet]
 * (列表来自该类型的 /discover/tags top-N),选完 feed 整页 replace 展示该标签的收藏榜。
 */
class TagRankFragment : TypeTabsRankFragment() {

    override val titleRes: Int get() = R.string.tag_rank_title

    override fun createPage(type: String): Fragment = TagRankPageFragment.newInstance(type)

    companion object {
        @JvmStatic
        fun newInstance(): TagRankFragment = TagRankFragment()
    }
}
