package ceui.pixiv.ui.detail

import androidx.fragment.app.Fragment
import ceui.lisa.R
import ceui.lisa.activities.followUser
import ceui.lisa.activities.unfollowUser
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellUserInfoBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.loxia.ObjectPool
import ceui.loxia.User
import ceui.loxia.findActionReceiverOrNull
import ceui.loxia.findFragmentOrNull
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.ui.user.UserActionReceiver
import ceui.pixiv.utils.setOnClick


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
                // 点击昵称直接复制；因为 View 有 onClickListener 后会消化事件，不会再冒泡
                // 到 root 触发「打开用户页」，两个短按语义可以共存。
                binding.userName.setOnClick {
                    val name = user?.name.orEmpty()
                    if (name.isNotEmpty()) {
                        Common.copy(it.context, name)
                        Common.showToast(it.context.getString(R.string.novel_author_name_copied))
                    }
                }
                // UID 行同理：点击复制 UID。
                binding.userInfo.setOnClick {
                    val uid = user?.id?.toString().orEmpty()
                    if (uid.isNotEmpty()) {
                        Common.copy(it.context, uid)
                        Common.showToast(it.context.getString(R.string.novel_author_id_copied))
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
