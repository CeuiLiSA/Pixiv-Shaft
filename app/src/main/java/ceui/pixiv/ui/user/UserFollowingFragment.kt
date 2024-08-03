package ceui.pixiv.ui.user

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellUserPreviewBinding
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.Client
import ceui.loxia.ObjectPool
import ceui.loxia.UserPreview
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.pixiv.ui.common.setUpStaggerLayout
import ceui.pixiv.ui.common.IllustCardHolder
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.refactor.viewBinding

class UserFollowingFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val args by navArgs<UserFollowingFragmentArgs>()
    private val viewModel by pixivListViewModel {
        DataSource(
            loader = { Client.appApi.getFollowingUsers(args.userId, args.restrictType) },
            mapper = { preview -> preview.illusts.map { IllustCardHolder(it) } }
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpStaggerLayout(binding, viewModel)
    }
}

class UserPreviewHolder(val userPreview: UserPreview) : ListItemHolder() {
    init {
        userPreview.user?.let {
            ObjectPool.update(it)
        }
    }
}

@ItemHolder(UserPreviewHolder::class)
class UserPreviewViewHolder(bd: CellUserPreviewBinding) : ListItemViewHolder<CellUserPreviewBinding, UserPreviewHolder>(bd) {
    override fun onBindViewHolder(holder: UserPreviewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.userName.text = holder.userPreview.user?.name
    }
}