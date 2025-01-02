package ceui.pixiv.ui.detail

import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import ceui.lisa.R
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellArtworkCaptionBinding
import ceui.loxia.DateParse
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder


class ArtworkCaptionHolder(val illustId: Long) : ListItemHolder() {
    override fun getItemId(): Long {
        return illustId
    }
}

@ItemHolder(ArtworkCaptionHolder::class)
class ArtworkCaptionViewHolder(bd: CellArtworkCaptionBinding) : ListItemViewHolder<CellArtworkCaptionBinding, ArtworkCaptionHolder>(bd) {

    override fun onBindViewHolder(holder: ArtworkCaptionHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val liveIllust = ObjectPool.get<Illust>(holder.illustId)
        binding.illust = liveIllust
        liveIllust.observe(lifecycleOwner) { illust ->
            if (illust.caption?.isNotEmpty() == true) {
                binding.caption.isVisible = true
                binding.caption.text = HtmlCompat.fromHtml(illust.caption, HtmlCompat.FROM_HTML_MODE_COMPACT)
            } else {
                binding.caption.isVisible = false
            }
            binding.publishTime.text = context.getString(
                R.string.published_on,
                DateParse.getTimeAgo(context, illust.create_date)
            )
        }
    }
}
