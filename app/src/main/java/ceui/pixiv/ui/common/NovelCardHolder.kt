package ceui.pixiv.ui.common

import androidx.core.view.isVisible
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellNovelCardBinding
import ceui.lisa.utils.V3Palette
import ceui.loxia.DateParse
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.loxia.ProgressIndicator
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.novel.NovelSeriesActionReceiver
import ceui.pixiv.ui.user.UserActionReceiver
import ceui.pixiv.utils.setOnClick

class NovelCardHolder(val novel: Novel) : ListItemHolder() {
    init {
        ObjectPool.update(novel)
        novel.user?.let {
            ObjectPool.update(it)
        }
    }

    override fun getItemId(): Long {
        return novel.id
    }
}

@ItemHolder(NovelCardHolder::class)
class NovelCardViewHolder(bd: CellNovelCardBinding) :
    ListItemViewHolder<CellNovelCardBinding, NovelCardHolder>(bd) {

    override fun onBindViewHolder(holder: NovelCardHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.novel = ObjectPool.get<Novel>(holder.novel.id)
        binding.showTags = Shaft.sSettings.isShowNovelCardTags

        val receiver = binding.root.findActionReceiverOrNull<NovelMultiSelectReceiver>()

        // Apply current multi-select visual state
        applyMultiSelectState(holder, receiver)

        binding.userLayout.setOnClick { sender ->
            if (receiver?.isNovelMultiSelectMode() == true) {
                receiver.onToggleNovelSelection(holder.novel.id)
                return@setOnClick
            }
            holder.novel.user?.id?.let {
                sender.findActionReceiverOrNull<UserActionReceiver>()?.onClickUser(it)
            }
        }
        binding.seriesName.setOnClick { sender ->
            if (receiver?.isNovelMultiSelectMode() == true) {
                receiver.onToggleNovelSelection(holder.novel.id)
                return@setOnClick
            }
            holder.novel.series?.let { series ->
                sender.findActionReceiverOrNull<NovelSeriesActionReceiver>()
                    ?.onClickNovelSeries(sender, series)
            }
        }
        binding.root.setOnClick {
            if (receiver?.isNovelMultiSelectMode() == true) {
                receiver.onToggleNovelSelection(holder.novel.id)
                return@setOnClick
            }
            it.findActionReceiverOrNull<NovelActionReceiver>()?.onClickNovel(holder.novel.id)
        }
        binding.bookmark.setOnClick {
            if (receiver?.isNovelMultiSelectMode() == true) {
                receiver.onToggleNovelSelection(holder.novel.id)
                return@setOnClick
            }
            it.findActionReceiverOrNull<NovelActionReceiver>()
                ?.onClickBookmarkNovel(it, holder.novel.id)
        }
        // Intercept touches on tags in multi-select mode to prevent
        // NavController crash in TemplateActivity
        binding.novelTag.setOnTouchListener { _, _ ->
            receiver?.isNovelMultiSelectMode() == true
        }
        binding.publishTime.text = context.getString(
            R.string.published_on,
            DateParse.getTimeAgo(context, holder.novel.create_date)
        )
        binding.textCount.text =
            context.getString(R.string.how_many_words, holder.novel.text_length.toString())
    }

    fun applyMultiSelectState(holder: NovelCardHolder, receiver: NovelMultiSelectReceiver?) {
        val inSelectMode = receiver?.isNovelMultiSelectMode() == true
        binding.selectIndicator.isVisible = inSelectMode
        if (inSelectMode) {
            val selected = receiver.isNovelSelected(holder.novel.id)
            val palette = V3Palette.from(context)
            binding.selectIndicator.setImageResource(
                if (selected) R.drawable.ic_check_circle_black_24dp
                else R.drawable.ic_checkbox_off
            )
            if (selected) {
                binding.selectIndicator.clearColorFilter()
            } else {
                binding.selectIndicator.setColorFilter(palette.textTag)
            }
        }
    }
}

interface NovelActionReceiver {
    fun onClickNovel(novelId: Long)
    fun onClickBookmarkNovel(sender: ProgressIndicator, novelId: Long)
    fun visitNovelById(novelId: Long)
}

interface NovelMultiSelectReceiver {
    fun isNovelMultiSelectMode(): Boolean
    fun isNovelSelected(novelId: Long): Boolean
    fun onToggleNovelSelection(novelId: Long)
}
