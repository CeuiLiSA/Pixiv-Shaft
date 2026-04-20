package ceui.pixiv.ui.novel

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.Client
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.task.FetchAllTask
import ceui.pixiv.ui.task.PixivTaskType
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widgets.MenuItem
import ceui.pixiv.widgets.showActionMenu

class NovelSeriesFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val seriesId: Long by lazy { arguments?.getLong(ARG_SERIES_ID, 0L) ?: 0L }
    private val viewModel by constructVM({ seriesId }) { id ->
        NovelSeriesViewModel(id)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, viewModel, ListMode.VERTICAL)
        binding.toolbarLayout.naviTitle.text = getString(R.string.novel_series)
        binding.toolbarLayout.naviMore.setOnClick {
            showActionMenu {
                add(
                    MenuItem(getString(R.string.download_all_artworks)) {
                        FetchAllTask(
                            requireActivity(),
                            taskFullName = "下载系列小说全部作品-${seriesId}",
                            PixivTaskType.DownloadSeriesNovels,
                        ) {
                            Client.appApi.getNovelSeries(seriesId)
                        }
                    }
                )
            }
        }
    }

    companion object {
        const val ARG_SERIES_ID = "series_id"

        fun newInstance(seriesId: Long): NovelSeriesFragment = NovelSeriesFragment().apply {
            arguments = Bundle().apply { putLong(ARG_SERIES_ID, seriesId) }
        }
    }
}
