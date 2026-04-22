package ceui.pixiv.ui.novel

import android.view.View
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellNovelActionsBinding
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.utils.setOnClick


/**
 * 小说详情页核心功能按钮行（任务 #4）。
 * 【分享】【评论】【下载】三个平权按钮，weight=1 均分宽度。
 * 下载按钮单击走预设默认格式，长按弹格式选择。
 */
class NovelActionsHolder(val novelId: Long) : ListItemHolder() {
    override fun getItemId(): Long = novelId
}

interface NovelActionsReceiver {
    fun onClickShareNovel(sender: View, novelId: Long)
    fun onClickNovelComments(sender: View, novelId: Long)
    fun onClickDownloadNovel(sender: View, novelId: Long)
    fun onLongClickDownloadNovel(sender: View, novelId: Long)
}

@ItemHolder(NovelActionsHolder::class)
class NovelActionsViewHolder(bd: CellNovelActionsBinding) :
    ListItemViewHolder<CellNovelActionsBinding, NovelActionsHolder>(bd) {

    override fun onBindViewHolder(holder: NovelActionsHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.btnShare.setOnClick {
            it.findActionReceiverOrNull<NovelActionsReceiver>()
                ?.onClickShareNovel(it, holder.novelId)
        }
        binding.btnComments.setOnClick {
            it.findActionReceiverOrNull<NovelActionsReceiver>()
                ?.onClickNovelComments(it, holder.novelId)
        }
        binding.btnDownload.setOnClick {
            it.findActionReceiverOrNull<NovelActionsReceiver>()
                ?.onClickDownloadNovel(it, holder.novelId)
        }
        binding.btnDownload.setOnLongClickListener { sender ->
            sender.findActionReceiverOrNull<NovelActionsReceiver>()
                ?.onLongClickDownloadNovel(sender, holder.novelId)
            true
        }
    }
}
