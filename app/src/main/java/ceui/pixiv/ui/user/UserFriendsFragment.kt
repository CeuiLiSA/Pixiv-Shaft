package ceui.pixiv.ui.user

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPagedListBinding
import ceui.loxia.Client
import ceui.loxia.KListShow
import ceui.loxia.UserPreview
import ceui.pixiv.paging.PagingAPIRepository
import ceui.pixiv.paging.pagingViewModel
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpPagedList
import ceui.pixiv.ui.common.viewBinding

class UserFriendsFragment : PixivFragment(R.layout.fragment_paged_list) {

    private val binding by viewBinding(FragmentPagedListBinding::bind)
    private val safeArgs by navArgs<UserFriendsFragmentArgs>()
    private val viewModel by pagingViewModel({ safeArgs.userId }) { userId ->
        object : PagingAPIRepository<UserPreview>() {
            override suspend fun loadFirst(): KListShow<UserPreview> {
                return Client.appApi.getUserPixivFriends(userId)
            }

            override fun mapper(entity: UserPreview): List<ListItemHolder> {
                return listOf(UserPreviewHolder(entity))
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpPagedList(binding, viewModel, ListMode.VERTICAL)
        binding.toolbarLayout.naviTitle.text = getString(R.string.string_323)
    }
}