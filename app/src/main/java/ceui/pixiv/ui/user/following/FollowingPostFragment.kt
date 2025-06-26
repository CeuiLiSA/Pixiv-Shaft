package ceui.pixiv.ui.user.following

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.utils.Common
import ceui.loxia.launchSuspend
import ceui.loxia.observeEvent
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.list.pixivListViewModel
import kotlinx.coroutines.delay

class FollowingPostFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val args by navArgs<FollowingPostFragmentArgs>()
    private val viewModel by pixivListViewModel { FollowingPostsDataSource(args) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, viewModel, ListMode.VERTICAL)

        val listView = binding.listView
        viewModel.typedDataSource<FollowingPostsDataSource>().fetchEvent.observeEvent(
            viewLifecycleOwner
        ) { index ->
            if (index > 0) {
                Common.showToast("更新了${index}条数据")
                launchSuspend {
                    delay(200L)
                    if (getView() != null) {
                        listView.smoothScrollToPosition(0)
                    }
                }
            }
        }
    }
}