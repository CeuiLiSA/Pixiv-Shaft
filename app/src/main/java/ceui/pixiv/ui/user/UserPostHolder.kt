package ceui.pixiv.ui.user

import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import ceui.lisa.activities.followUser
import ceui.lisa.activities.unfollowUser
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellUserPostBinding
import ceui.lisa.utils.Params
import ceui.loxia.DateParse
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.loxia.User
import ceui.loxia.clearItemDecorations
import ceui.loxia.findActionReceiverOrNull
import ceui.loxia.findFragmentOrNull
import ceui.pixiv.ui.chats.SquareUrlHolder
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.IllustCardActionReceiver
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.screenWidth
import ceui.pixiv.utils.setOnClick
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
        if (holder.illust.page_count == 1) {
            val imageView = binding.image
            binding.imageList.isVisible = false
            imageView.isVisible = true
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
        } else {
            binding.image.isVisible = false
            binding.imageList.isVisible = true
            val spanCount = if (holder.illust.page_count <= 4) {
                2
            } else {
                3
            }
            binding.imageList.layoutManager = GridLayoutManager(context, spanCount)
            val adapter = CommonAdapter(lifecycleOwner)
            binding.imageList.adapter = adapter
            binding.imageList.clearItemDecorations()
            binding.imageList.addItemDecoration(GridSpacingItemDecoration(spanCount, 2.ppppx))
            adapter.submitList(holder.illust.meta_pages?.take(9)?.mapIndexedNotNull { index, page ->
                if (page.image_urls?.large != null) {
                    SquareUrlHolder(page.image_urls.large, holder.illust, index)
                } else {
                    null
                }
            }) {
                binding.imageList.requestLayout()
            }
        }

        binding.postTime.text = DateParse.getTimeAgo(context, holder.illust.create_date)
        binding.user = ObjectPool.get<User>(holder.illust.user?.id ?: 0L)
        binding.holder = holder
    }
}