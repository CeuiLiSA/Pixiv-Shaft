package ceui.pixiv.ui.user

import androidx.core.view.updateLayoutParams
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellUserPostBinding
import ceui.loxia.DateParse
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.loxia.User
import ceui.loxia.findActionReceiverOrNull
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
        binding.root.setOnClick {
            it.findActionReceiverOrNull<IllustCardActionReceiver>()
                ?.onClickIllustCard(holder.illust)
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