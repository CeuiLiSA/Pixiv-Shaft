package ceui.pixiv.ui.user

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.Client
import ceui.loxia.ObjectPool
import ceui.loxia.ObjectType
import ceui.loxia.User
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.pixiv.ui.common.IllustCardHolder
import ceui.pixiv.ui.common.setUpRefreshState

import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.bulk.AuthorWorksFetcher
import ceui.pixiv.ui.bulk.FetchProgressDialog
import ceui.pixiv.ui.bulk.QueueDownloadManager
import ceui.pixiv.ui.task.FetchAllTask
import ceui.pixiv.ui.task.PixivTaskType
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widgets.MenuItem
import ceui.pixiv.widgets.showActionMenu

class UserCreatedIllustsFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val args by navArgs<UserCreatedIllustsFragmentArgs>()
    private val viewModel by pixivListViewModel {
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
                    MenuItem("下载全部作品", "流式抓取 + 持久化队列，可冷启动恢复") {
                        val authorName = ObjectPool.get<User>(args.userId).value?.name ?: "user"
                        val taskName = "下载 ${authorName} 的全部${if (args.objectType == "manga") "漫画" else "插画"}"
                        val fetcher = AuthorWorksFetcher(
                            userId = args.userId,
                            type = args.objectType,
                            taskName = taskName,
                        )
                        FetchProgressDialog.show(childFragmentManager, fetcher.fetch())
                        QueueDownloadManager.notifyNewItems()
                    }
                )
                add(
                    MenuItem("收藏全部作品", "实验性功能，测试中") {
                        FetchAllTask(requireActivity(), taskFullName = "收藏${ObjectPool.get<User>(args.userId).value?.name}创作的全部插画", PixivTaskType.BookmarkAll) {
                            Client.appApi.getUserCreatedIllusts(
                                args.userId,
                                args.objectType
                            )
                        }
                    }
                )
            }
        }
    }
}
