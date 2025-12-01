package ceui.pixiv.ui.common

import android.view.View
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import ceui.lisa.R
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellIllustCardBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.loxia.ProgressIndicator
import ceui.loxia.Series
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.screenWidth
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide
import timber.log.Timber
import kotlin.math.roundToInt

class IllustCardHolder(val illust: Illust, val isBlocked: Boolean = false) : ListItemHolder() {

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
    fun onClickBookmarkIllust(sender: ProgressIndicator, illustId: Long)
    fun visitIllustById(illustId: Long)
}

interface IllustSeriesActionReceiver {
    fun onClickIllustSeries(sender: View, series: Series)
}

interface IllustIdActionReceiver {
    fun onClickIllust(illustId: Long)
}

@ItemHolder(IllustCardHolder::class)
class IllustCardViewHolder(bd: CellIllustCardBinding) :
    ListItemViewHolder<CellIllustCardBinding, IllustCardHolder>(bd) {

    override fun onBindViewHolder(holder: IllustCardHolder, position: Int) {
        super.onBindViewHolder(holder, position)

        binding.illust = ObjectPool.get<Illust>(holder.illust.id)

        val itemWidth = ((screenWidth - 12.ppppx) / 2F).roundToInt()
        Timber.d("dsaadssw22 ${holder.illust.height}, ${holder.illust.width}")
        val itemHeight =
            (itemWidth * holder.illust.height / holder.illust.width.toFloat()).roundToInt()
        binding.image.updateLayoutParams {
            width = itemWidth
            height = itemHeight
        }

        if (holder.illust.page_count > 1) {
            binding.pSize.isVisible = true
            binding.pSize.text = "${holder.illust.page_count}P"
        } else {
            binding.pSize.isVisible = false
        }

        Glide.with(binding.root.context)
            .load(GlideUrlChild(holder.illust.image_urls?.large))
            .placeholder(R.drawable.bg_loading_placeholder)
            .into(binding.image)
        binding.image.setOnClick {
            it.findActionReceiverOrNull<IllustCardActionReceiver>()
                ?.onClickIllustCard(holder.illust)
        }
        binding.bookmark.setOnClick {
            it.findActionReceiverOrNull<IllustCardActionReceiver>()
                ?.onClickBookmarkIllust(it, holder.illust.id)
        }
    }
}
