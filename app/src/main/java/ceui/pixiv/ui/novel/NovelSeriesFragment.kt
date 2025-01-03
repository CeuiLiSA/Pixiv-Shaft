package ceui.pixiv.ui.novel

import android.os.Bundle
import android.view.View
import ceui.pixiv.ui.common.PixivFragment
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.threadSafeArgs
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.common.viewBinding
import kotlin.getValue

class NovelSeriesFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val safeArgs by threadSafeArgs<NovelSeriesFragmentArgs>()
    private val viewModel by constructVM({ safeArgs.seriesId }) { seriesId->
        NovelSeriesViewModel(seriesId)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, viewModel, ListMode.VERTICAL)
        viewModel.series.observe(viewLifecycleOwner) {
            binding.toolbarLayout.naviTitle.text = it.novel_series_detail?.title
        }
    }
}