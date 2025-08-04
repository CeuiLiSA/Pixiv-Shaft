package ceui.pixiv.ui.user

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPagedListBinding
import ceui.loxia.Client
import ceui.loxia.ObjectPool
import ceui.loxia.User
import ceui.loxia.threadSafeArgs
import ceui.pixiv.paging.PagingIllustAPIRepository
import ceui.pixiv.paging.pagingViewModel
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpPagedList
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.task.FetchAllTask
import ceui.pixiv.ui.task.PixivTaskType
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widgets.MenuItem
import ceui.pixiv.widgets.showActionMenu

class UserCreatedIllustsFragment : PixivFragment(R.layout.fragment_paged_list) {

    private val binding by viewBinding(FragmentPagedListBinding::bind)
    private val safeArgs by threadSafeArgs<UserCreatedIllustsFragmentArgs>()
    private val viewModel by pagingViewModel({ safeArgs }) { args ->
        PagingIllustAPIRepository {
            Client.appApi.getUserCreatedIllusts(args.userId, args.objectType)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpPagedList(binding, viewModel)
        binding.toolbarLayout.naviMore.setOnClick {
            showActionMenu {
                add(
                    MenuItem("下载全部作品", "实验性功能，测试中") {
                        FetchAllTask(
                            requireActivity(),
                            taskFullName = "下载${ObjectPool.get<User>(safeArgs.userId).value?.name}创作的全部插画",
                            PixivTaskType.DownloadAll
                        ) {
                            Client.appApi.getUserCreatedIllusts(
                                safeArgs.userId,
                                safeArgs.objectType
                            )
                        }
                    }
                )
                add(
                    MenuItem("收藏全部作品", "实验性功能，测试中") {
                        FetchAllTask(
                            requireActivity(),
                            taskFullName = "收藏${ObjectPool.get<User>(safeArgs.userId).value?.name}创作的全部插画",
                            PixivTaskType.BookmarkAll
                        ) {
                            Client.appApi.getUserCreatedIllusts(
                                safeArgs.userId,
                                safeArgs.objectType
                            )
                        }
                    }
                )
//                add(
//                    MenuItem("关闭瀑布流", "实验性功能，测试中") {
//                        launchSuspend {
//                            (binding.listView.adapter as? CommonAdapter)?.let {
//                                it.submitList(listOf()) {
//                                    viewModel.dataSource().updateMapper(mapper = { item ->
//                                        listOf(UserPostHolder(item as Illust))
//                                    })
//                                    setUpLayoutManager(binding.listView, ListMode.VERTICAL)
//                                    viewModel.dataSource().mapProtoItemsToHolders()
//                                }
//                            }
//                        }
//                    }
//                )
//
//                add(
//                    MenuItem("打开瀑布流", "实验性功能，测试中") {
//                        launchSuspend {
//                            (binding.listView.adapter as? CommonAdapter)?.let {
//                                it.submitList(listOf()) {
//                                    viewModel.dataSource().updateMapper(mapper = { item ->
//                                        listOf(IllustCardHolder(item as Illust))
//                                    })
//                                    setUpLayoutManager(binding.listView, ListMode.STAGGERED_GRID)
//                                    viewModel.dataSource().mapProtoItemsToHolders()
//                                }
//                            }
//                        }
//                    }
//                )
            }
        }
    }
}
