package ceui.pixiv.ui.chats

import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.ItemIllustSquareBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.loxia.Illust
import ceui.loxia.WebIllust
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.IllustCardActionReceiver
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide


class IllustSquareV2Holder(val illust: Illust) : ListItemHolder() {

    override fun getItemId(): Long {
        return illust.id
    }
}


@ItemHolder(IllustSquareV2Holder::class)
class IllustSquareV2ViewHolder(aa: ItemIllustSquareBinding) :
    ListItemViewHolder<ItemIllustSquareBinding, IllustSquareV2Holder>(aa) {

    override fun onBindViewHolder(holder: IllustSquareV2Holder, position: Int) {
        super.onBindViewHolder(holder, position)
        Glide.with(context)
            .load(GlideUrlChild(holder.illust.image_urls?.square_medium))
            .into(binding.squareImage)
        binding.squareImage.setOnClick {
            it.findActionReceiverOrNull<IllustCardActionReceiver>()
                ?.onClickIllustCard(holder.illust)
        }
    }
}