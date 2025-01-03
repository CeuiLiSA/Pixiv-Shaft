package ceui.pixiv.ui.novel

import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import ceui.lisa.R
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellArtworkCaptionBinding
import ceui.lisa.databinding.CellNovelCaptionBinding
import ceui.loxia.DateParse
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder


class NovelCaptionHolder(val novelId: Long) : ListItemHolder() {
    override fun getItemId(): Long {
        return novelId
    }
}

@ItemHolder(NovelCaptionHolder::class)
class NovelCaptionViewHolder(bd: CellNovelCaptionBinding) : ListItemViewHolder<CellNovelCaptionBinding, NovelCaptionHolder>(bd) {

    override fun onBindViewHolder(holder: NovelCaptionHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val liveNovel = ObjectPool.get<Novel>(holder.novelId)
        binding.novel = liveNovel
        liveNovel.observe(lifecycleOwner) { novel ->
            if (novel.caption?.isNotEmpty() == true) {
                binding.caption.isVisible = true
                binding.caption.text = HtmlCompat.fromHtml(novel.caption, HtmlCompat.FROM_HTML_MODE_COMPACT)
            } else {
                binding.caption.isVisible = false
            }
            binding.publishTime.text = context.getString(
                R.string.published_on,
                DateParse.getTimeAgo(context, novel.create_date)
            )
        }
    }
}
