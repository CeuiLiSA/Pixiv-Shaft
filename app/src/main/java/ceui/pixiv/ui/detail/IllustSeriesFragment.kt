package ceui.pixiv.ui.detail

import android.os.Bundle
import android.view.View
import ceui.pixiv.ui.common.PixivFragment
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.ObjectType
import ceui.loxia.pushFragment
import ceui.loxia.threadSafeArgs
import ceui.pixiv.ui.comments.CommentsFragmentArgs
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.novel.NovelSeriesFragmentArgs
import ceui.pixiv.ui.novel.NovelSeriesViewModel
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widgets.MenuItem
import ceui.pixiv.widgets.showActionMenu
import kotlin.getValue

class IllustSeriesFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val safeArgs by threadSafeArgs<IllustSeriesFragmentArgs>()
    private val viewModel by constructVM({ safeArgs.seriesId }) { seriesId->
        IllustSeriesViewModel(seriesId)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, viewModel, ListMode.VERTICAL)
        binding.toolbarLayout.naviTitle.text = getString(R.string.novel_series)
        binding.toolbarLayout.naviMore.setOnClick {
            showActionMenu {
                add(
                    MenuItem(getString(R.string.download_all_artworks)) {

                    }
                )
            }
        }
    }
}