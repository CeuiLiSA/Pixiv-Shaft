package ceui.pixiv.ui.home

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.settings.BookmarkSurface

/**
 * 「推荐漫画」页（TemplateActivity 宿主）：推荐 feed 的漫画变体 + 自带 toolbar
 * （对齐 legacy FragmentRecmdIllust("漫画")：showToolbar() 仅漫画分支返回 true）。
 */
class RecmdMangaFeedFragment : RecmdIllustFeedFragment(R.layout.fragment_toolbar_feed) {

    override val bookmarkSurface: BookmarkSurface get() = BookmarkSurface.HOME_MANGA

    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding, feedBinding.feedListView)
        // 对齐 legacy getToolbarTitle：推荐 + dataType
        binding.toolbarTitle.text = getString(R.string.recommend) + dataType
    }

    companion object {
        @JvmStatic
        fun newInstance(): RecmdMangaFeedFragment {
            return RecmdMangaFeedFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_DATA_TYPE, TYPE_MANGA)
                }
            }
        }
    }
}
