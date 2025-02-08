package ceui.pixiv.ui.user

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.Client
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.NovelCardHolder
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.task.FetchAllTask
import ceui.pixiv.ui.task.PixivTaskType
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widgets.MenuItem
import ceui.pixiv.widgets.showActionMenu

class UserCreatedNovelFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val args by navArgs<UserCreatedNovelFragmentArgs>()
    private val viewModel by pixivListViewModel { DataSource(
        dataFetcher = { Client.appApi.getUserCreatedNovels(args.userId) },
        itemMapper = { novel -> listOf(NovelCardHolder(novel)) },
        filter = { novel -> novel.visible != false }
    ) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, viewModel, ListMode.VERTICAL)
        binding.toolbarLayout.naviMore.setOnClick {
            showActionMenu {
                add(
                    MenuItem(getString(R.string.download_all_created_novels)) {
                        FetchAllTask(requireActivity(), taskFullName = "下载用户全部小说作品-${args.userId}", PixivTaskType.DownloadSeriesNovels) {
                            Client.appApi.getUserCreatedNovels(
                                args.userId,
                            )
                        }
                    }
                )
            }
        }
    }
}