package ceui.pixiv.ui.chats

import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.ItemIllustSquareBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.loxia.WebIllust
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.IllustCardActionReceiver
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide


class IllustSquareHolder(val illust: WebIllust) : ListItemHolder() {

    override fun getItemId(): Long {
        return illust.id
    }
}


@ItemHolder(IllustSquareHolder::class)
class IllustSquareViewHolder(aa: ItemIllustSquareBinding) :
    ListItemViewHolder<ItemIllustSquareBinding, IllustSquareHolder>(aa) {

    override fun onBindViewHolder(holder: IllustSquareHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        Glide.with(context)
            .load(GlideUrlChild(holder.illust.url))
            .into(binding.squareImage)
        binding.squareImage.setOnClick {
            it.findActionReceiverOrNull<IllustCardActionReceiver>()
                ?.onClickIllustCard(holder.illust.toIllust())
        }
    }
}