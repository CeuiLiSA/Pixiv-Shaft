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
import ceui.pixiv.PixivFragment
import ceui.pixiv.pixivListViewModel
import ceui.pixiv.setUpLinearLayout
import ceui.pixiv.setUpStaggerLayout
import ceui.pixiv.ui.IllustCardHolder
import ceui.refactor.ListItemHolder
import ceui.refactor.ListItemViewHolder
import ceui.refactor.viewBinding

class UserFollowingFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val args by navArgs<UserFollowingFragmentArgs>()
    private val viewModel by pixivListViewModel(
        loader = { Client.appApi.getFollowingUsers(args.userId, args.restrictType) },
        mapper = { preview -> preview.illusts?.map { IllustCardHolder(it) } ?: listOf() }
    )

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