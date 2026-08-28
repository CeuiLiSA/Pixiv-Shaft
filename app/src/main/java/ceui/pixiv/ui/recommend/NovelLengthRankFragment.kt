package ceui.pixiv.ui.recommend

import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import ceui.lisa.R

/**
 * 长篇小说榜 — 打自建服务端 shaft-api-v2 的 discover/most-bookmarked?type=novel&length=。
 * 三个固定 tab:长篇(≥5 万字)/ 中篇(2–5 万)/ 短篇(<2 万),按 pixiv 总收藏数排(含 R-18),
 * 长篇在前(这个入口的卖点就是长篇)。单 tab 是 [BookmarkRankNovelFeedFragment] 带 length。
 * 宿主契约见 [TypeTabsRankFragment]。
 */
class NovelLengthRankFragment : TypeTabsRankFragment() {

    override val titleRes: Int get() = R.string.novel_length_rank_title

    /** length 值是服务端 enum;顺序即 tab 顺序:长篇在前。 */
    override val types: List<String>
        get() = listOf(NOVEL_LENGTH_LONG, NOVEL_LENGTH_MEDIUM, NOVEL_LENGTH_SHORT)

    @StringRes
    override fun tabTitleRes(type: String): Int = when (type) {
        NOVEL_LENGTH_MEDIUM -> R.string.novel_length_medium
        NOVEL_LENGTH_SHORT -> R.string.novel_length_short
        else -> R.string.novel_length_long
    }

    override fun createPage(type: String): Fragment =
        BookmarkRankNovelFeedFragment.newInstance(length = type)

    companion object {
        @JvmStatic
        fun newInstance(): NovelLengthRankFragment = NovelLengthRankFragment()
    }
}
