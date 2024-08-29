package ceui.pixiv.ui.user

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
import ceui.refactor.setOnClick

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
        binding.postTime.text = DateParse.getTimeAgo(context, holder.illust.create_date)
        binding.user = ObjectPool.get<User>(holder.illust.user?.id ?: 0L)
        binding.holder = holder
    }
}