package ceui.pixiv.ui.common

import android.view.View
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
class NovelCardViewHolder(bd: CellNovelCardBinding) : ListItemViewHolder<CellNovelCardBinding, NovelCardHolder>(bd) {

    override fun onBindViewHolder(holder: NovelCardHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.novel = ObjectPool.get<Novel>(holder.novel.id)
        // Respect the user setting that hides tags on novel cards. This
        // has to be a layout binding variable rather than a post-bind
        // View.visibility override: `novel` is a LiveData and any future
        // emission would otherwise re-evaluate visibleOrGone and flip the
        // view back to VISIBLE.
        binding.showTags = Shaft.sSettings.isShowNovelCardTags

        // Multi-select mode: if the host fragment implements
        // NovelMultiSelectReceiver AND has multi-select enabled, show a
        // checkbox at the start of the card and route taps to toggle
        // selection instead of opening the novel detail. Default is off so
        // every other caller (home, search, user pages) keeps its normal
        // behavior.
        val multiSelectReceiver = binding.root.findActionReceiverOrNull<NovelMultiSelectReceiver>()
        val isMultiSelect = multiSelectReceiver?.isNovelMultiSelectMode() == true
        binding.selectIndicator.isVisible = isMultiSelect
        if (isMultiSelect) {
            val selected = multiSelectReceiver?.isNovelSelected(holder.novel.id) == true
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

        binding.userLayout.setOnClick { sender ->
            if (isMultiSelect) {
                multiSelectReceiver?.onToggleNovelSelection(holder.novel.id)
                return@setOnClick
            }
            holder.novel.user?.id?.let {
                sender.findActionReceiverOrNull<UserActionReceiver>()?.onClickUser(it)
            }
        }
        binding.seriesName.setOnClick { sender ->
            if (isMultiSelect) {
                multiSelectReceiver?.onToggleNovelSelection(holder.novel.id)
                return@setOnClick
            }
            holder.novel.series?.let { series ->
                sender.findActionReceiverOrNull<NovelSeriesActionReceiver>()?.onClickNovelSeries(sender, series)
            }
        }
        binding.root.setOnClick {
            if (isMultiSelect) {
                multiSelectReceiver?.onToggleNovelSelection(holder.novel.id)
                return@setOnClick
            }
            it.findActionReceiverOrNull<NovelActionReceiver>()?.onClickNovel(holder.novel.id)
        }
        binding.bookmark.setOnClick {
            if (isMultiSelect) {
                multiSelectReceiver?.onToggleNovelSelection(holder.novel.id)
                return@setOnClick
            }
            it.findActionReceiverOrNull<NovelActionReceiver>()
                ?.onClickBookmarkNovel(it, holder.novel.id)
        }
        binding.publishTime.text = context.getString(
            R.string.published_on,
            DateParse.getTimeAgo(context, holder.novel.create_date)
        )
        binding.textCount.text =
            context.getString(R.string.how_many_words, holder.novel.text_length.toString())
    }
}

interface NovelActionReceiver {
    fun onClickNovel(novelId: Long)
    fun onClickBookmarkNovel(sender: ProgressIndicator, novelId: Long)
    fun visitNovelById(novelId: Long)
}

/**
 * Optional opt-in receiver a fragment can implement to turn any rendered
 * [NovelCardHolder] into a multi-select cell. When [isNovelMultiSelectMode]
 * returns true, taps on the card toggle selection instead of opening the
 * novel detail. Adding a new implementation elsewhere requires NO changes
 * to any other existing caller of NovelCardHolder — the checkbox stays
 * hidden by default.
 */
interface NovelMultiSelectReceiver {
    fun isNovelMultiSelectMode(): Boolean
    fun isNovelSelected(novelId: Long): Boolean
    fun onToggleNovelSelection(novelId: Long)
}