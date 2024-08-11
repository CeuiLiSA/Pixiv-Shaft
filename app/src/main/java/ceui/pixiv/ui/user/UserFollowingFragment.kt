package ceui.pixiv.ui.user

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.databinding.BindingAdapter
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellUserPreviewBinding
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.loxia.Client
import ceui.loxia.ObjectPool
import ceui.loxia.User
import ceui.loxia.UserPreview
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.pixiv.ui.common.setUpStaggerLayout
import ceui.pixiv.ui.common.IllustCardHolder
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.refactor.viewBinding
import com.bumptech.glide.Glide

class UserFollowingFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val args by navArgs<UserFollowingFragmentArgs>()
    private val viewModel by pixivListViewModel {
        DataSource(
            dataFetcher = { Client.appApi.getFollowingUsers(args.userId, args.restrictType) },
            itemMapper = { preview -> preview.illusts.map { IllustCardHolder(it) } }
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

    override fun areItemsTheSame(other: ListItemHolder): Boolean {
        return userPreview.user?.id == (other as? UserPreviewHolder)?.userPreview?.user?.id
    }

    override fun areContentsTheSame(other: ListItemHolder): Boolean {
        return userPreview == (other as? UserPreviewHolder)?.userPreview
    }
}

@ItemHolder(UserPreviewHolder::class)
class UserPreviewViewHolder(bd: CellUserPreviewBinding) :
    ListItemViewHolder<CellUserPreviewBinding, UserPreviewHolder>(bd) {
    override fun onBindViewHolder(holder: UserPreviewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.holder = holder
        binding.root.setOnClickListener { sender ->
            holder.userPreview.user?.id?.let {
                sender.findActionReceiverOrNull<UserActionReceiver>()?.onClickUser(it)
            }
        }
    }
}

const val NO_PROFILE_IMG = "https://s.pximg.net/common/images/no_profile.png"

@BindingAdapter("userIcon")
fun ImageView.binding_loadUserIcon(user: User?) {
    val url = user?.profile_image_urls?.medium ?: return
    scaleType = ImageView.ScaleType.CENTER_CROP
    if (url == NO_PROFILE_IMG) {
        Glide.with(this)
            .load(R.drawable.icon_user_mask)
            .into(this)
    } else {
        Glide.with(this)
            .load(GlideUrlChild(url))
            .placeholder(R.drawable.icon_user_mask)
            .into(this)
    }
}

fun TextView.setTextOrGone(content: String?) {
    if (content?.isNotEmpty() == true) {
        isVisible = true
        text = content
    } else {
        isVisible = false
    }
}