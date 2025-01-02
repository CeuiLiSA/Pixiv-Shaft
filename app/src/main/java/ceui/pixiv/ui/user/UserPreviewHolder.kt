package ceui.pixiv.ui.user

import androidx.fragment.app.Fragment
import ceui.lisa.activities.followUser
import ceui.lisa.activities.unfollowUser
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellUserPreviewBinding
import ceui.lisa.utils.Params
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.loxia.User
import ceui.loxia.UserPreview
import ceui.loxia.findActionReceiverOrNull
import ceui.loxia.findFragmentOrNull
import ceui.pixiv.ui.common.IllustCardActionReceiver
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.utils.setOnClick

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