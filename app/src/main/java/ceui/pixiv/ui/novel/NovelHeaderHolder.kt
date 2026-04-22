package ceui.pixiv.ui.novel

import android.content.Intent
import android.view.View
import android.widget.TextView
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
        binding.seriesName.setOnClick { sender ->
            liveNovel.value?.series?.let { series ->
                sender.findActionReceiverOrNull<NovelSeriesActionReceiver>()?.onClickNovelSeries(sender, series)
            }
        }
        binding.title.setOnClick {
            Common.copy(context, liveNovel.value?.title)
        }
        // 用户反馈：作品详情页希望提供一组高密度可复制 chip，含作品ID/作者/作者ID/发布时间/字数/浏览/收藏。
        liveNovel.observe(lifecycleOwner) { novel ->
            if (novel != null) bindInfoChips(novel)
        }
    }

    private fun bindInfoChips(novel: Novel) {
        chip(binding.chipNovelId, R.string.novel_chip_id, novel.id.toString(), novel.id.toString())
        novel.user?.let { user ->
            val name = user.name.orEmpty()
            chip(binding.chipAuthor, R.string.novel_chip_author, name, name)
            chip(binding.chipAuthorId, R.string.novel_chip_author_id, user.id.toString(), user.id.toString())
        } ?: run {
            binding.chipAuthor.isVisible = false
            binding.chipAuthorId.isVisible = false
        }
        novel.create_date?.let {
            val display = it.replace('T', ' ').take(16)
            chip(binding.chipCreateDate, R.string.novel_chip_create_date, display, it)
        } ?: run { binding.chipCreateDate.isVisible = false }
        novel.text_length?.let {
            chip(binding.chipTextLength, R.string.novel_chip_text_length, it.toString(), it.toString())
        } ?: run { binding.chipTextLength.isVisible = false }
        novel.total_view?.let {
            chip(binding.chipTotalView, R.string.novel_chip_total_view, it.toString(), it.toString())
        } ?: run { binding.chipTotalView.isVisible = false }
        novel.total_bookmarks?.let {
            chip(binding.chipTotalBookmarks, R.string.novel_chip_total_bookmarks, it.toString(), it.toString())
        } ?: run { binding.chipTotalBookmarks.isVisible = false }
    }

    private fun chip(view: TextView, labelRes: Int, displayValue: String, copyValue: String) {
        view.text = context.getString(labelRes, displayValue)
        view.isVisible = true
        view.setOnClick { Common.copy(context, copyValue) }
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
