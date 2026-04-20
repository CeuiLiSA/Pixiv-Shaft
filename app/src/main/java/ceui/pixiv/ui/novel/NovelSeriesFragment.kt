package ceui.pixiv.ui.novel

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.ObjectPool
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.createResponseStore
import ceui.pixiv.ui.common.pixivValueViewModel
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.task.FetchAllTask
import ceui.pixiv.ui.task.PixivTaskType
import ceui.pixiv.ui.works.blurBackground
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widgets.MenuItem
import ceui.pixiv.widgets.showActionMenu

class NovelSeriesFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val seriesId: Long by lazy { arguments?.getLong(ARG_SERIES_ID, 0L) ?: 0L }
    private val viewModel by constructVM({ seriesId }) { id ->
        NovelSeriesViewModel(id)
    }
    private val bgViewModel by pixivValueViewModel(
        dataFetcher = {
            Client.appApi.getUserBookmarkedIllusts(
                SessionManager.loggedInUid,
                Params.TYPE_PUBLIC
            )
        },
        responseStore = createResponseStore({ "user-${SessionManager.loggedInUid}-bookmarked-illusts" })
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, viewModel, ListMode.VERTICAL)
        bgViewModel.result.observe(viewLifecycleOwner) { resp ->
            resp.displayList.getOrNull(seriesId.mod(10))?.let {
                ObjectPool.update(it)
                blurBackground(binding, it.id)
            }
        }
        binding.toolbarLayout.root.visibility = View.GONE
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
