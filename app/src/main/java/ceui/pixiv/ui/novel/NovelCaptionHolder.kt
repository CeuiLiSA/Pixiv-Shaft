package ceui.pixiv.ui.novel

import android.widget.TextView
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import ceui.lisa.R
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellNovelCaptionBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.ShareIllust
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.IllustCardActionReceiver
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.ui.common.NOVEL_URL_HEAD
import ceui.pixiv.ui.common.NovelActionReceiver
import ceui.pixiv.utils.extractPixivId
import ceui.pixiv.utils.setOnClick
import timber.log.Timber


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
            bindInfoChips(novel)

            val rawCaption = novel.caption.orEmpty()
            val hasCaption = rawCaption.isNotEmpty()
            // Pixiv 的 caption 里 `\n` 和 `<br>` 经常混用，HtmlCompat 只认后者，
            // 不转就会把几十段挤成一段（issue 里系列详情的投诉，普通详情页同源同修）。
            val normalizedCaption = rawCaption.replace("\r\n", "\n").replace("\n", "<br/>")
            if (hasCaption) {
                binding.caption.isVisible = true
                // 启用链接点击处理
                binding.caption.movementMethod = CustomLinkMovementMethod { link ->
                    val info = extractPixivId(link)
                    if (info.type == "novels") {
                        info.value.toLongOrNull()?.let { id ->
                            binding.caption.findActionReceiverOrNull<NovelActionReceiver>()?.visitNovelById(id)
                        }
                    } else if (info.type == "illusts") {
                        info.value.toLongOrNull()?.let { id ->
                            binding.caption.findActionReceiverOrNull<IllustCardActionReceiver>()?.visitIllustById(id)
                        }
                    }
                    Timber.d("sdasdwq2 ${info}")
                }
                binding.caption.text = HtmlCompat.fromHtml(normalizedCaption, HtmlCompat.FROM_HTML_MODE_COMPACT)
            } else {
                binding.caption.isVisible = false
            }
            binding.copyCaption.isVisible = hasCaption
            binding.copyCaption.setOnClick {
                val plain = HtmlCompat.fromHtml(normalizedCaption, HtmlCompat.FROM_HTML_MODE_COMPACT)
                    .toString().trim()
                Common.copy(context, plain)
            }
        }
    }

    private fun bindInfoChips(novel: Novel) {
        chip(binding.chipNovelId, R.string.novel_chip_id, novel.id.toString(), novel.id.toString())
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
