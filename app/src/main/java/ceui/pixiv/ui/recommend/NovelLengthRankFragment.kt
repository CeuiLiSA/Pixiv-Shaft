package ceui.pixiv.ui.recommend

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.viewpager.widget.ViewPager
import ceui.lisa.R
import ceui.lisa.databinding.FragmentYearRankBinding
import ceui.pixiv.ui.common.viewBinding

/**
 * 长篇小说榜 — 打自建服务端 shaft-api-v2 的 discover/most-bookmarked?type=novel&length=。
 * 三个固定 tab:长篇(≥5 万字)/ 中篇(2–5 万)/ 短篇(<2 万),按 pixiv 总收藏数排(含 R-18),
 * 长篇在前(这个入口的卖点就是长篇)。单 tab 是 [FilteredBookmarkRankNovelFeedFragment]。
 *
 * 复用 fragment_year_rank 布局(toolbar + tabs + pager,同 [WallpaperRankFragment]);tab 本地
 * 写死,yearsLoading 直接 GONE。FSPA + RESUME_ONLY_CURRENT + 子 fragment autoLoad=false 的
 * 组合同 [YearRankFragment]。
 */
class NovelLengthRankFragment : Fragment(R.layout.fragment_year_rank) {

    private val binding by viewBinding(FragmentYearRankBinding::bind)

    private var currentPos: Int = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        savedInstanceState?.let {
            currentPos = it.getInt(KEY_POS, 0)
        }

        binding.toolbar.title = " "
        binding.toolbarTitle.text = getString(R.string.novel_length_rank_title)
        binding.toolbar.setNavigationOnClickListener { activity?.finish() }
        binding.yearsLoading.visibility = View.GONE

        binding.viewPager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) { currentPos = position }
        })

        // length 值是服务端 enum;标题是本地化文案。顺序即 tab 顺序:长篇在前。
        val lengths = listOf(NOVEL_LENGTH_LONG, NOVEL_LENGTH_MEDIUM, NOVEL_LENGTH_SHORT)
        val titles = listOf(
            getString(R.string.novel_length_long),
            getString(R.string.novel_length_medium),
            getString(R.string.novel_length_short),
        )

        binding.viewPager.adapter = object : FragmentStatePagerAdapter(
            childFragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT
        ) {
            override fun getItem(position: Int): Fragment =
                FilteredBookmarkRankNovelFeedFragment.newInstance(month = null, length = lengths[position])
            override fun getCount(): Int = titles.size
            override fun getPageTitle(position: Int): CharSequence = titles[position]
        }
        binding.tabLayout.setupWithViewPager(binding.viewPager)
        val pos = currentPos.coerceIn(0, titles.size - 1)
        currentPos = pos
        binding.viewPager.setCurrentItem(pos, false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_POS, currentPos)
    }

    companion object {
        private const val KEY_POS = "novel_length_rank_pos"

        @JvmStatic
        fun newInstance(): NovelLengthRankFragment = NovelLengthRankFragment()
    }
}
