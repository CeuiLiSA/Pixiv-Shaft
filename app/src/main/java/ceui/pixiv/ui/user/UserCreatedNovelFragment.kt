package ceui.pixiv.ui.user

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPagedListBinding
import ceui.loxia.Client
import ceui.loxia.KListShow
import ceui.loxia.Novel
import ceui.pixiv.paging.PagingAPIRepository
import ceui.pixiv.paging.pagingViewModel
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.NovelCardHolder
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpPagedList
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.task.FetchAllTask
import ceui.pixiv.ui.task.PixivTaskType
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widgets.MenuItem
import ceui.pixiv.widgets.showActionMenu

class UserCreatedNovelFragment : PixivFragment(R.layout.fragment_paged_list) {

    private val binding by viewBinding(FragmentPagedListBinding::bind)
    private val safeArgs by navArgs<UserCreatedNovelFragmentArgs>()
    private val viewModel by pagingViewModel({ safeArgs.userId }) { userId ->
        object : PagingAPIRepository<Novel>() {
            override suspend fun loadFirst(): KListShow<Novel> {
                return Client.appApi.getUserCreatedNovels(userId)
            }

            override fun mapper(entity: Novel): List<ListItemHolder> {
                return listOf(NovelCardHolder(entity))
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpPagedList(binding, viewModel, ListMode.VERTICAL)
        binding.toolbarLayout.naviMore.setOnClick {
            showActionMenu {
                add(
                    MenuItem(getString(R.string.download_all_created_novels)) {
                        FetchAllTask(
                            requireActivity(),
                            taskFullName = "下载用户全部小说作品-${safeArgs.userId}",
                            PixivTaskType.DownloadSeriesNovels
                        ) {
                            Client.appApi.getUserCreatedNovels(
                                safeArgs.userId,
                            )
                        }
                    }
                )
            }
        }
    }
}