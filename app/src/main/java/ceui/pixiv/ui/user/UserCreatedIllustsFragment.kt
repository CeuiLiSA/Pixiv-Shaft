package ceui.pixiv.ui.user

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.loxia.User
import ceui.loxia.launchSuspend
import ceui.loxia.threadSafeArgs
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.IllustCardHolder
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpLayoutManager
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.pixiv.ui.task.FetchAllTask
import ceui.pixiv.ui.task.PixivTaskType
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widgets.MenuItem
import ceui.pixiv.widgets.showActionMenu

class UserCreatedIllustsFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val safeArgs by threadSafeArgs<UserCreatedIllustsFragmentArgs>()
    private val viewModel by pixivListViewModel({ safeArgs }) { args ->
        DataSource(
            dataFetcher = { Client.appApi.getUserCreatedIllusts(args.userId, args.objectType) },
            itemMapper = { illust -> listOf(IllustCardHolder(illust)) }
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, viewModel)
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
                add(
                    MenuItem("关闭瀑布流", "实验性功能，测试中") {
                        launchSuspend {
                            (binding.listView.adapter as? CommonAdapter)?.let {
                                it.submitList(listOf()) {
                                    viewModel.dataSource().updateMapper(mapper = { item ->
                                        listOf(UserPostHolder(item as Illust))
                                    })
                                    setUpLayoutManager(binding.listView, ListMode.VERTICAL)
                                    viewModel.dataSource().mapProtoItemsToHolders()
                                }
                            }
                        }
                    }
                )

                add(
                    MenuItem("打开瀑布流", "实验性功能，测试中") {
                        launchSuspend {
                            (binding.listView.adapter as? CommonAdapter)?.let {
                                it.submitList(listOf()) {
                                    viewModel.dataSource().updateMapper(mapper = { item ->
                                        listOf(IllustCardHolder(item as Illust))
                                    })
                                    setUpLayoutManager(binding.listView, ListMode.STAGGERED_GRID)
                                    viewModel.dataSource().mapProtoItemsToHolders()
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}
