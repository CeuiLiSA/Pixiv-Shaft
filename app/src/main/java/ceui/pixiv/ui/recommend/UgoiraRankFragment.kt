package ceui.pixiv.ui.recommend

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import ceui.lisa.R
import ceui.lisa.databinding.FragmentRankPickerBinding
import ceui.pixiv.ui.common.viewBinding

/**
 * 动图收藏榜 — pixiv 给所有动图自动打「うごイラ」标签,所以这就是标签专区固定
 * tag=うごイラ 的 discover/most-bookmarked,不需要新接口。
 *
 * 复用 fragment_rank_picker 布局(toolbar + feed_container),但 tag 是写死的:选择条与
 * loading 直接 GONE,首装 replace 一个带 tag 的 [BookmarkRankIllustFeedFragment];重建路径 child 由 FM
 * 自己带回来,不重复装(否则会重拉首屏)。
 */
class UgoiraRankFragment : Fragment(R.layout.fragment_rank_picker) {

    private val binding by viewBinding(FragmentRankPickerBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.title = " "
        binding.toolbarTitle.text = getString(R.string.ugoira_rank_title)
        binding.toolbar.setNavigationOnClickListener { activity?.finish() }
        binding.rankSelector.visibility = View.GONE
        binding.rankLoading.visibility = View.GONE

        if (childFragmentManager.findFragmentById(R.id.feed_container) == null) {
            childFragmentManager.commit {
                replace(
                    R.id.feed_container,
                    BookmarkRankIllustFeedFragment.newInstance(type = RankType.ILLUST, tag = UGOIRA_TAG),
                )
            }
        }
    }

    companion object {
        /** pixiv 官方自动标签原文(服务端 enum 语义),别本地化。 */
        private const val UGOIRA_TAG = "うごイラ"

        @JvmStatic
        fun newInstance(): UgoiraRankFragment = UgoiraRankFragment()
    }
}
