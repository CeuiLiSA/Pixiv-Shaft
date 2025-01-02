package ceui.pixiv.ui.trending

import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellTrendingTagBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.loxia.TrendingTag
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide

class TrendingTagHolder(val trendingTag: TrendingTag) : ListItemHolder() {

    override fun areItemsTheSame(other: ListItemHolder): Boolean {
        return trendingTag.illust?.id == (other as? TrendingTagHolder)?.trendingTag?.illust?.id
    }

    override fun areContentsTheSame(other: ListItemHolder): Boolean {
        return trendingTag == (other as? TrendingTagHolder)?.trendingTag
    }
}

@ItemHolder(TrendingTagHolder::class)
class TrendingTagViewHolder(bd: CellTrendingTagBinding) :
    ListItemViewHolder<CellTrendingTagBinding, TrendingTagHolder>(bd) {

    override fun onBindViewHolder(holder: TrendingTagHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.holder = holder
        Glide.with(context)
            .load(GlideUrlChild(holder.trendingTag.illust?.image_urls?.square_medium))
            .into(binding.image)
        binding.root.setOnClick {
            it.findActionReceiverOrNull<TrendingTagActionReceiver>()?.onClickTrendingTag(holder.trendingTag)
        }
        binding.root.setOnLongClickListener {
            it.findActionReceiverOrNull<TrendingTagActionReceiver>()?.onLongClickTrendingTag(holder.trendingTag)
            true
        }
    }
}

interface TrendingTagActionReceiver {
    fun onClickTrendingTag(trendingTag: TrendingTag)
    fun onLongClickTrendingTag(trendingTag: TrendingTag)
}