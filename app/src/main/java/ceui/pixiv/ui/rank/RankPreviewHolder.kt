package ceui.pixiv.ui.rank

import androidx.core.view.updateLayoutParams
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.ItemIllustSquareBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.loxia.Illust
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.IllustCardActionReceiver
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide



class RankPreviewHolder(val illust: Illust) : ListItemHolder() {

    override fun getItemId(): Long {
        return illust.id
    }
}


@ItemHolder(RankPreviewHolder::class)
class RankPreviewViewHolder(aa: ItemIllustSquareBinding) :
    ListItemViewHolder<ItemIllustSquareBinding, RankPreviewHolder>(aa) {

    override fun onBindViewHolder(holder: RankPreviewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        Glide.with(context)
            .load(GlideUrlChild(holder.illust.image_urls?.square_medium))
            .into(binding.squareImage)
        val w = 170.ppppx
        binding.root.updateLayoutParams {
            width = w
            height = w
        }
        binding.squareImage.setOnClick {
            it.findActionReceiverOrNull<IllustCardActionReceiver>()
                ?.onClickIllustCard(holder.illust)
        }
    }
}