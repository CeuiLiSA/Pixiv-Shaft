package ceui.pixiv.ui.novel

import android.content.Intent
import android.view.View
import androidx.core.view.isVisible
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellNovelHeaderBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.loxia.Series
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.ui.common.NovelActionReceiver
import ceui.pixiv.utils.setOnClick
import java.text.NumberFormat


class NovelHeaderHolder(val novelId: Long) : ListItemHolder() {
    override fun getItemId(): Long {
        return novelId
    }
}

@ItemHolder(NovelHeaderHolder::class)
class NovelHeaderViewHolder(bd: CellNovelHeaderBinding) : ListItemViewHolder<CellNovelHeaderBinding, NovelHeaderHolder>(bd) {

    override fun onBindViewHolder(holder: NovelHeaderHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val liveNovel = ObjectPool.get<Novel>(holder.novelId)
        binding.novel = liveNovel
        binding.bookmark.setOnClick {
            it.findActionReceiverOrNull<NovelActionReceiver>()
                ?.onClickBookmarkNovel(it, holder.novelId)
        }
        binding.bookmark.setOnLongClickListener { sender ->
            val novel = liveNovel.value ?: return@setOnLongClickListener false
            openTagBookmarkForNovel(sender, novel)
            true
        }
        binding.seriesStrip.setOnClick { sender ->
            liveNovel.value?.series?.let { series ->
                sender.findActionReceiverOrNull<NovelSeriesActionReceiver>()?.onClickNovelSeries(sender, series)
            }
        }
        binding.title.setOnClick {
            Common.copy(context, liveNovel.value?.title)
        }
        liveNovel.observe(lifecycleOwner) { novel ->
            if (novel == null) return@observe
            // Meta line
            val date = novel.create_date?.replace('T', ' ')?.take(16).orEmpty()
            binding.metaDate.text = date
            val wordCount = novel.text_length
            if (wordCount != null && wordCount > 0) {
                binding.metaWordCount.text = context.getString(
                    R.string.novel_meta_word_count,
                    NumberFormat.getInstance().format(wordCount),
                )
                binding.metaWordCount.isVisible = true
                binding.metaDot2.isVisible = true
            } else {
                binding.metaWordCount.isVisible = false
                binding.metaDot2.isVisible = false
            }
        }
    }
}

interface NovelSeriesActionReceiver {
    fun onClickNovelSeries(sender: View, series: Series)
}

internal fun openTagBookmarkForNovel(sender: View, novel: Novel) {
    val ctx = sender.context
    val tagNames = novel.tags.orEmpty().mapNotNull { it.name }.toTypedArray()
    val intent = Intent(ctx, TemplateActivity::class.java).apply {
        putExtra(TemplateActivity.EXTRA_FRAGMENT, "按标签收藏")
        putExtra(Params.ILLUST_ID, novel.id.toInt())
        putExtra(Params.DATA_TYPE, Params.TYPE_NOVEL)
        putExtra(Params.TAG_NAMES, tagNames)
    }
    ctx.startActivity(intent)
}
