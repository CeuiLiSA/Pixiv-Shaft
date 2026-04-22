package ceui.pixiv.ui.novel

import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellNovelCaptionBinding
import ceui.lisa.utils.Common
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.IllustCardActionReceiver
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
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
                // 任务 #5：移除独立"复制简介"提示，点击简介正文直接复制纯文本。
                binding.caption.setOnClick {
                    val plain = HtmlCompat.fromHtml(normalizedCaption, HtmlCompat.FROM_HTML_MODE_COMPACT)
                        .toString().trim()
                    Common.copy(context, plain)
                }
            } else {
                binding.caption.isVisible = false
            }
        }
    }
}
