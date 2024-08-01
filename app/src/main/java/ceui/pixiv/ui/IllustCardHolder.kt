package ceui.pixiv.ui

import androidx.core.view.updateLayoutParams
import ceui.lisa.R
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellIllustCardBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.loxia.findActionReceiverOrNull
import ceui.refactor.ListItemHolder
import ceui.refactor.ListItemViewHolder
import ceui.refactor.ppppx
import ceui.refactor.screenWidth
import ceui.refactor.setOnClick
import com.bumptech.glide.Glide
import kotlin.math.roundToInt

class IllustCardHolder(val illust: Illust) : ListItemHolder() {

    init {
        ObjectPool.update(illust)
        illust.user?.let {
            ObjectPool.update(it)
        }
    }

    override fun areItemsTheSame(other: ListItemHolder): Boolean {
        return illust.id == (other as? IllustCardHolder)?.illust?.id
    }

    override fun areContentsTheSame(other: ListItemHolder): Boolean {
        return illust == (other as? IllustCardHolder)?.illust
    }
}

interface IllustCardActionReceiver {
    fun onClickIllustCard(illust: Illust)
}

@ItemHolder(IllustCardHolder::class)
class IllustCardViewHolder(bd: CellIllustCardBinding) :
    ListItemViewHolder<CellIllustCardBinding, IllustCardHolder>(bd) {

    override fun onBindViewHolder(holder: IllustCardHolder, position: Int) {
        super.onBindViewHolder(holder, position)

        val itemWidth = ((screenWidth - 12.ppppx) / 2F).roundToInt()
        val itemHeight =
            (itemWidth * holder.illust.height / holder.illust.width.toFloat()).roundToInt()
        binding.image.updateLayoutParams {
            width = itemWidth
            height = itemHeight
        }

        Glide.with(binding.root.context)
            .load(GlideUrlChild(holder.illust.image_urls?.large))
            .placeholder(R.drawable.bg_loading_placeholder)
            .into(binding.image)
        binding.image.setOnClick {
            it.findActionReceiverOrNull<IllustCardActionReceiver>()
                ?.onClickIllustCard(holder.illust)
        }
    }
}