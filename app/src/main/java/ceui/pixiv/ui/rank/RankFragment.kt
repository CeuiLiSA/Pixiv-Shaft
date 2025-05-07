package ceui.pixiv.ui.rank

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import ceui.lisa.R
import ceui.lisa.databinding.FragmentRankViewpagerBinding
import ceui.loxia.ObjectType
import ceui.loxia.threadSafeArgs
import ceui.pixiv.ui.circles.PagedFragmentItem
import ceui.pixiv.ui.circles.SmartFragmentPagerAdapter
import ceui.pixiv.ui.common.TitledViewPagerFragment
import ceui.pixiv.widgets.setUpWith
import ceui.pixiv.ui.common.viewBinding

class RankFragment : TitledViewPagerFragment(R.layout.fragment_rank_viewpager) {

    private val binding by viewBinding(FragmentRankViewpagerBinding::bind)
    private val safeArgs by threadSafeArgs<RankFragmentArgs>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.rootLayout.updatePadding(0, insets.top, 0, 0)
            windowInsets
        }
        val seeds = if (safeArgs.objectType == ObjectType.ILLUST) {
            listOf(
                "day",
                "week",
                "month",
                "day_ai",
                "day_male",
                "day_female",
                "week_original",
                "week_rookie"
            )
        } else {
            listOf(
                "day_manga",
                "week_manga",
                "month_manga",
                "week_rookie_manga"
            )
        }
        val adapter = SmartFragmentPagerAdapter(
            seeds.map { str ->
                PagedFragmentItem(
                    builder = {
                        RankingIllustsFragment().apply {
                            arguments = RankingIllustsFragmentArgs(str).toBundle()
                        }
                    }, initialTitle = str
                )
            }, this
        )
        binding.rankViewpager.adapter = adapter
        binding.tabLayoutList.setUpWith(
            binding.rankViewpager, binding.slidingCursor, viewLifecycleOwner, {})
    }
}