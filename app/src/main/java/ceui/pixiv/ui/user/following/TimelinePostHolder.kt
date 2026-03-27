package ceui.pixiv.ui.user.following

import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import ceui.lisa.activities.followUser
import ceui.lisa.activities.unfollowUser
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellTimelineDateHeaderBinding
import ceui.lisa.databinding.CellTimelinePostBinding
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
import ceui.pixiv.ui.user.GridSpacingItemDecoration
import ceui.pixiv.ui.user.UserActionReceiver
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.screenWidth
import ceui.pixiv.utils.setOnClick
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

class TimelinePostHolder(
    val illust: Illust,
    val isFirst: Boolean = false,
    val isLast: Boolean = false
) : ListItemHolder() {
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

@ItemHolder(TimelinePostHolder::class)
class TimelinePostViewHolder(bd: CellTimelinePostBinding) :
    ListItemViewHolder<CellTimelinePostBinding, TimelinePostHolder>(bd) {

    override fun onBindViewHolder(holder: TimelinePostHolder, position: Int) {
        super.onBindViewHolder(holder, position)

        // Timeline line visibility
        binding.lineTop.isVisible = !holder.isFirst
        binding.lineBottom.isVisible = !holder.isLast

        // Click listeners
        binding.card.setOnClick {
            it.findActionReceiverOrNull<IllustCardActionReceiver>()
                ?.onClickIllustCard(holder.illust)
        }
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

        // Follow/unfollow
        val uid = holder.illust.user?.id
        uid?.let {
            binding.follow.setOnClick { sender ->
                sender.findFragmentOrNull<Fragment>()
                    ?.followUser(sender, it.toInt(), Params.TYPE_PUBLIC)
            }
            binding.unfollow.setOnClick { sender ->
                sender.findFragmentOrNull<Fragment>()?.unfollowUser(sender, it.toInt())
            }
        }

        // Image handling
        val cardContentWidth = screenWidth - 52.ppppx - 24.ppppx - 12.ppppx
        if (holder.illust.page_count == 1) {
            binding.image.isVisible = true
            binding.imageList.isVisible = false
            binding.pageCount.isVisible = false

            val w = cardContentWidth
            val h = (w * holder.illust.height.toFloat() / holder.illust.width.toFloat())
                .roundToInt()
                .coerceAtMost((screenWidth * 0.75f).roundToInt())
            binding.image.updateLayoutParams {
                width = w
                height = h
            }
        } else {
            binding.image.isVisible = false
            binding.imageList.isVisible = true
            binding.pageCount.isVisible = true
            binding.pageCount.text = "${holder.illust.page_count}P"

            val spanCount = if (holder.illust.page_count <= 4) 2 else 3
            binding.imageList.layoutManager = GridLayoutManager(context, spanCount)
            val adapter = CommonAdapter(lifecycleOwner)
            binding.imageList.adapter = adapter
            binding.imageList.clearItemDecorations()
            binding.imageList.addItemDecoration(GridSpacingItemDecoration(spanCount, 2.ppppx))
            adapter.submitList(
                holder.illust.meta_pages?.take(9)?.mapIndexedNotNull { index, page ->
                    if (page.image_urls?.large != null) {
                        SquareUrlHolder(page.image_urls.large, holder.illust, index)
                    } else {
                        null
                    }
                }
            ) {
                binding.imageList.requestLayout()
            }
        }

        // Time
        binding.postTime.text = DateParse.getTimeAgo(context, holder.illust.create_date)

        // Stats
        val numberFormat = NumberFormat.getNumberInstance(Locale.getDefault())
        binding.viewCount.text = numberFormat.format(holder.illust.total_view ?: 0)
        binding.bookmarkCount.text = numberFormat.format(holder.illust.total_bookmarks ?: 0)

        // Data binding
        binding.user = ObjectPool.get<User>(holder.illust.user?.id ?: 0L)
        binding.holder = holder
    }
}

class TimelineDateHeaderHolder(
    val dateText: String,
    val isFirst: Boolean = false
) : ListItemHolder() {

    override fun getItemId(): Long {
        return dateText.hashCode().toLong()
    }

    override fun areItemsTheSame(other: ListItemHolder): Boolean {
        return dateText == (other as? TimelineDateHeaderHolder)?.dateText
    }

    override fun areContentsTheSame(other: ListItemHolder): Boolean {
        return dateText == (other as? TimelineDateHeaderHolder)?.dateText
    }
}

@ItemHolder(TimelineDateHeaderHolder::class)
class TimelineDateHeaderViewHolder(bd: CellTimelineDateHeaderBinding) :
    ListItemViewHolder<CellTimelineDateHeaderBinding, TimelineDateHeaderHolder>(bd) {

    override fun onBindViewHolder(holder: TimelineDateHeaderHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.dateText.text = holder.dateText
        binding.lineTop.isVisible = !holder.isFirst
    }
}
