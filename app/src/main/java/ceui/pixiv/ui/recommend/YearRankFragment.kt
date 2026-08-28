package ceui.pixiv.ui.recommend

import androidx.fragment.app.Fragment
import ceui.lisa.R

/**
 * 年代榜 — 打自建服务端 shaft-api-v2 的 discover/most-bookmarked?type=&year=YYYY。
 * 顶部 插画 / 漫画 / 小说 三个类型 tab([TypeTabsRankFragment]),每个 tab 一页
 * [YearRankPageFragment]:选择条显当前年份 + 作品数,点它弹 [RankPickerSheet]
 * (年份列表来自该类型的 /discover/years),选完 feed 整页 replace。
 *
 * 此前是横向 ViewPager,每年一个 tab —— 20 个年份 tab 横滑挑不动,与标签专区一并改成
 * sheet 选择(契约论证见 git history);类型 tab 只有三个,才配得上横向 tab。
 */
class YearRankFragment : TypeTabsRankFragment() {

    override val titleRes: Int get() = R.string.year_rank_title

    override fun createPage(type: String): Fragment = YearRankPageFragment.newInstance(type)

    companion object {
        @JvmStatic
        fun newInstance(): YearRankFragment = YearRankFragment()
    }
}
