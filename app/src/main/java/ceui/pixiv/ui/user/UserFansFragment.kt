package ceui.pixiv.ui.user

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPagedListBinding
import ceui.loxia.Client
import ceui.pixiv.paging.PagingUserAPIRepository
import ceui.pixiv.paging.pagingViewModel
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpPagedList
import ceui.pixiv.ui.common.viewBinding

class UserFansFragment : PixivFragment(R.layout.fragment_paged_list) {

    private val binding by viewBinding(FragmentPagedListBinding::bind)
    private val safeArgs by navArgs<UserFansFragmentArgs>()
    private val viewModel by pagingViewModel({ safeArgs.userId }) { userId ->
        PagingUserAPIRepository {
            Client.appApi.getUserFans(userId)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpPagedList(binding, viewModel, ListMode.VERTICAL)
        binding.toolbarLayout.naviTitle.text = getString(R.string.string_322)
    }
}