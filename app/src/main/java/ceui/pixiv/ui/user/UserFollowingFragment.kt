package ceui.pixiv.ui.user

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.databinding.BindingAdapter
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.activities.followUser
import ceui.lisa.activities.unfollowUser
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellUserPreviewBinding
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.loxia.User
import ceui.loxia.UserPreview
import ceui.loxia.findActionReceiverOrNull
import ceui.loxia.findFragmentOrNull
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.IllustCardActionReceiver
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.pixiv.ui.common.setUpStaggerLayout
import ceui.pixiv.ui.common.IllustCardHolder
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.refactor.setOnClick
import ceui.refactor.viewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions

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
        userPreview.illusts.forEach {
            ObjectPool.update(it)
        }
    }

    override fun areItemsTheSame(other: ListItemHolder): Boolean {
        return userPreview.user?.id == (other as? UserPreviewHolder)?.userPreview?.user?.id
    }

    override fun areContentsTheSame(other: ListItemHolder): Boolean {
        return userPreview == (other as? UserPreviewHolder)?.userPreview
    }

    val illust0: Illust? get() {
        return userPreview.illusts.getOrNull(0)
    }
    val illust1: Illust? get() {
        return userPreview.illusts.getOrNull(1)
    }
    val illust2: Illust? get() {
        return userPreview.illusts.getOrNull(2)
    }
}

@ItemHolder(UserPreviewHolder::class)
class UserPreviewViewHolder(bd: CellUserPreviewBinding) :
    ListItemViewHolder<CellUserPreviewBinding, UserPreviewHolder>(bd) {
    override fun onBindViewHolder(holder: UserPreviewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.holder = holder
        holder.userPreview.user?.id?.let {
            binding.user = ObjectPool.get<User>(it)
        }
        binding.root.setOnClickListener { sender ->
            holder.userPreview.user?.id?.let {
                sender.findActionReceiverOrNull<UserActionReceiver>()?.onClickUser(it)
            }
        }
        binding.follow.setOnClick { sender ->
            holder.userPreview.user?.id?.let {
                sender.findFragmentOrNull<Fragment>()?.followUser(sender, it.toInt(), Params.TYPE_PUBLIC)
            }
        }
        binding.unfollow.setOnClick { sender ->
            holder.userPreview.user?.id?.let {
                sender.findFragmentOrNull<Fragment>()?.unfollowUser(sender, it.toInt())
            }
        }
        binding.illust1.setOnClick { sender ->
            holder.illust0?.let {
                sender.findActionReceiverOrNull<IllustCardActionReceiver>()?.onClickIllustCard(it)
            }
        }
        binding.illust2.setOnClick { sender ->
            holder.illust1?.let {
                sender.findActionReceiverOrNull<IllustCardActionReceiver>()?.onClickIllustCard(it)
            }
        }
        binding.illust3.setOnClick { sender ->
            holder.illust2?.let {
                sender.findActionReceiverOrNull<IllustCardActionReceiver>()?.onClickIllustCard(it)
            }
        }
    }
}

const val NO_PROFILE_IMG = "https://s.pximg.net/common/images/no_profile.png"

@BindingAdapter("userIcon")
fun ImageView.binding_loadUserIcon(user: User?) {
    val url = user?.profile_image_urls?.findMaxSizeUrl() ?: return
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

@BindingAdapter("loadSquareMedia")
fun ImageView.binding_loadSquareMedia(illust: Illust?) {
    val url = illust?.image_urls?.square_medium ?: return
    scaleType = ImageView.ScaleType.CENTER_CROP
    Glide.with(this)
        .load(GlideUrlChild(url))
        .placeholder(R.drawable.image_place_holder_r2)
        .into(this)
}

fun TextView.setTextOrGone(content: String?) {
    if (content?.isNotEmpty() == true) {
        isVisible = true
        text = content
    } else {
        isVisible = false
    }
}