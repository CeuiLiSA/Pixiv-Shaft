package ceui.pixiv.ui.chats

import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.ItemUrlSquareBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.loxia.Illust
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.IllustCardActionReceiver
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide


class SquareUrlHolder(val url: String, val illust: Illust, val index: Int) : ListItemHolder() {

    override fun getItemId(): Long {
        return url.hashCode().toLong()
    }
}


@ItemHolder(SquareUrlHolder::class)
class SquareUrlViewHolder(aa: ItemUrlSquareBinding) :
    ListItemViewHolder<ItemUrlSquareBinding, SquareUrlHolder>(aa) {

    override fun onBindViewHolder(holder: SquareUrlHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        Glide.with(context)
            .load(GlideUrlChild(holder.url))
            .into(binding.squareImage)
        binding.squareImage.setOnClick {
            it.findActionReceiverOrNull<IllustCardActionReceiver>()
                ?.onClickIllustCard(holder.illust)
        }
    }
}