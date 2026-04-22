package ceui.pixiv.ui.novel

import android.content.Intent
import android.view.View
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellNovelHeaderBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.loxia.Series
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.ImageUrlViewer
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.ui.common.NovelActionReceiver
import ceui.pixiv.utils.setOnClick


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
        // 长按收藏按钮 → 打开「按标签收藏」以自定义标签/公开私密（issue #839）。
        binding.bookmark.setOnLongClickListener { sender ->
            val novel = liveNovel.value ?: return@setOnLongClickListener false
            openTagBookmarkForNovel(sender, novel)
            true
        }
        binding.novelCover.setOnClick { sender ->
            liveNovel.value?.image_urls?.findMaxSizeUrl()?.let { url ->
                ImageUrlViewer.open(sender.context, url, "novel_${holder.novelId}_cover")
            }
        }
        binding.seriesName.setOnClick { sender ->
            liveNovel.value?.series?.let { series ->
                sender.findActionReceiverOrNull<NovelSeriesActionReceiver>()?.onClickNovelSeries(sender, series)
            }
        }
        binding.title.setOnClick {
            Common.copy(context, liveNovel.value?.title)
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
        // FragmentSB 对 illust/novel 统一用 ILLUST_ID 作为 id key，type 区分。
        putExtra(Params.ILLUST_ID, novel.id.toInt())
        putExtra(Params.DATA_TYPE, Params.TYPE_NOVEL)
        putExtra(Params.TAG_NAMES, tagNames)
    }
    ctx.startActivity(intent)
}