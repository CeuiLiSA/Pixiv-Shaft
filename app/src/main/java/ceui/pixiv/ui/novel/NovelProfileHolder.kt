package ceui.pixiv.ui.novel

import android.widget.TextView
import androidx.core.view.isVisible
import ceui.lisa.R
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellNovelProfileBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.ShareIllust
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.ui.common.NOVEL_URL_HEAD
import ceui.pixiv.utils.setOnClick
import java.text.NumberFormat


class NovelProfileHolder(val novelId: Long) : ListItemHolder() {
    override fun getItemId(): Long {
        return novelId
    }
}

@ItemHolder(NovelProfileHolder::class)
class NovelProfileViewHolder(bd: CellNovelProfileBinding) :
    ListItemViewHolder<CellNovelProfileBinding, NovelProfileHolder>(bd) {

    override fun onBindViewHolder(holder: NovelProfileHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val liveNovel = ObjectPool.get<Novel>(holder.novelId)
        liveNovel.observe(lifecycleOwner) { novel ->
            if (novel == null) return@observe
            bindStats(novel)
            bindInfoChips(novel)
        }
    }

    private fun bindStats(novel: Novel) {
        val fmt = NumberFormat.getInstance()
        binding.statViews.text = fmt.format(novel.total_view ?: 0)
        binding.statBookmarks.text = fmt.format(novel.total_bookmarks ?: 0)
    }

    private fun bindInfoChips(novel: Novel) {
        chip(binding.chipNovelId, R.string.novel_chip_id, novel.id.toString(), novel.id.toString())
        novel.text_length?.let {
            chip(binding.chipTextLength, R.string.novel_chip_text_length, it.toString(), it.toString())
        } ?: run { binding.chipTextLength.isVisible = false }
        novel.total_view?.let {
            chip(binding.chipTotalView, R.string.novel_chip_total_view, it.toString(), it.toString())
        } ?: run { binding.chipTotalView.isVisible = false }
        novel.total_bookmarks?.let {
            chip(binding.chipTotalBookmarks, R.string.novel_chip_total_bookmarks, it.toString(), it.toString())
        } ?: run { binding.chipTotalBookmarks.isVisible = false }
        novel.create_date?.let {
            val display = it.replace('T', ' ').take(16)
            chip(binding.chipCreateDate, R.string.novel_chip_create_date, display, it)
        } ?: run { binding.chipCreateDate.isVisible = false }
        novel.user?.let { user ->
            val name = user.name.orEmpty()
            chip(binding.chipAuthor, R.string.novel_chip_author, name, name)
            chip(binding.chipAuthorId, R.string.novel_chip_author_id, user.id.toString(), user.id.toString())
            linkChip(binding.chipUserLink, R.string.novel_chip_user_link, ShareIllust.USER_URL_Head + user.id)
        } ?: run {
            binding.chipAuthor.isVisible = false
            binding.chipAuthorId.isVisible = false
            binding.chipUserLink.isVisible = false
        }
        linkChip(binding.chipNovelLink, R.string.novel_chip_novel_link, NOVEL_URL_HEAD + novel.id)
    }

    private fun chip(view: TextView, labelRes: Int, displayValue: String, copyValue: String) {
        view.text = context.getString(labelRes, displayValue)
        view.isVisible = true
        view.setOnClick { Common.copy(context, copyValue) }
    }

    private fun linkChip(view: TextView, labelRes: Int, url: String) {
        view.text = context.getString(labelRes)
        view.isVisible = true
        view.setOnClick { Common.copy(context, url) }
    }
}
