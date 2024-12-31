package ceui.pixiv.ui.user

import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import ceui.lisa.activities.followUser
import ceui.lisa.activities.unfollowUser
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellUserPostBinding
import ceui.lisa.utils.Params
import ceui.loxia.DateParse
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.loxia.User
import ceui.loxia.findActionReceiverOrNull
import ceui.loxia.findFragmentOrNull
import ceui.pixiv.ui.common.IllustCardActionReceiver
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.refactor.ppppx
import ceui.refactor.screenWidth
import ceui.refactor.setOnClick
import timber.log.Timber
import kotlin.math.roundToInt

class UserPostHolder(val illust: Illust) : ListItemHolder() {
    init {
        ObjectPool.update(illust)
        illust.user?.let {
            ObjectPool.update(it)
        }
    }

    override fun getItemId(): Long {
        return illust.id
    }
}

@ItemHolder(UserPostHolder::class)
class UserPostViewHolder(bd: CellUserPostBinding) :
    ListItemViewHolder<CellUserPostBinding, UserPostHolder>(bd) {

    override fun onBindViewHolder(holder: UserPostHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.image.setOnClick {
            it.findActionReceiverOrNull<IllustCardActionReceiver>()
                ?.onClickIllustCard(holder.illust)
        }
        binding.userIcon.setOnClick {
            it.findActionReceiverOrNull<UserActionReceiver>()
                ?.onClickUser(holder.illust.user?.id ?: 0L)
        }
        binding.userName.setOnClick {
            it.findActionReceiverOrNull<UserActionReceiver>()
                ?.onClickUser(holder.illust.user?.id ?: 0L)
        }
        val uid = holder.illust.user?.id
        uid?.let {
            binding.follow.setOnClick { sender ->
                sender.findFragmentOrNull<Fragment>()?.followUser(sender, it.toInt(), Params.TYPE_PUBLIC)
            }
            binding.unfollow.setOnClick { sender ->
                sender.findFragmentOrNull<Fragment>()?.unfollowUser(sender, it.toInt())
            }
        }
        val imageView = binding.image
        val w = if (holder.illust.width > holder.illust.height) {
            screenWidth - 36.ppppx
        } else {
            (screenWidth * 0.65F).roundToInt()
        }
        val h = (w * holder.illust.height.toFloat() / holder.illust.width.toFloat()).roundToInt()
        imageView.updateLayoutParams {
            width = w
            height = h
        }
        binding.postTime.text = DateParse.getTimeAgo(context, holder.illust.create_date)
        binding.user = ObjectPool.get<User>(holder.illust.user?.id ?: 0L)
        binding.holder = holder
    }
}