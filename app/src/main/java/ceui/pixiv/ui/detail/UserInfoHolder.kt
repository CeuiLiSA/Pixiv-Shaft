package ceui.pixiv.ui.detail

import androidx.fragment.app.Fragment
import ceui.lisa.activities.followUser
import ceui.lisa.activities.unfollowUser
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellUserInfoBinding
import ceui.lisa.utils.Params
import ceui.loxia.ObjectPool
import ceui.loxia.User
import ceui.loxia.findActionReceiverOrNull
import ceui.loxia.findFragmentOrNull
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.ui.user.UserActionReceiver
import ceui.refactor.setOnClick


class UserInfoHolder(val uid: Long) : ListItemHolder() {
    override fun getItemId(): Long {
        return uid
    }
}

@ItemHolder(UserInfoHolder::class)
class UserInfoViewHolder(bd: CellUserInfoBinding) : ListItemViewHolder<CellUserInfoBinding, UserInfoHolder>(bd) {

    override fun onBindViewHolder(holder: UserInfoHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val liveUser = ObjectPool.get<User>(holder.uid)
        binding.user = liveUser
        lifecycleOwner?.let {
            liveUser.observe(it) { user ->
                binding.root.setOnClickListener { sender ->
                    user?.id?.let {
                        sender.findActionReceiverOrNull<UserActionReceiver>()?.onClickUser(it)
                    }
                }
                binding.follow.setOnClick { sender ->
                    user?.id?.let {
                        sender.findFragmentOrNull<Fragment>()?.followUser(sender, it.toInt(), Params.TYPE_PUBLIC)
                    }
                }
                binding.unfollow.setOnClick { sender ->
                    user?.id?.let {
                        sender.findFragmentOrNull<Fragment>()?.unfollowUser(sender, it.toInt())
                    }
                }
            }
        }
    }
}
