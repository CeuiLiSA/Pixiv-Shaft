package ceui.pixiv.ui.novel

import android.text.TextUtils
import android.text.method.LinkMovementMethod
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import ceui.lisa.R
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellArtworkCaptionBinding
import ceui.lisa.databinding.CellNovelCaptionBinding
import ceui.lisa.utils.Common
import ceui.loxia.Client
import ceui.loxia.DateParse
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.loxia.findActionReceiverOrNull
import ceui.loxia.findFragmentOrNull
import ceui.loxia.launchSuspend
import ceui.pixiv.ui.common.IllustCardActionReceiver
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.ui.common.NovelActionReceiver
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.detail.ArtworksMap
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
            if (novel.caption?.isNotEmpty() == true) {
                binding.caption.isVisible = true
                // 启用链接点击处理
                // 设置自定义的 MovementMethod
                binding.caption.movementMethod = CustomLinkMovementMethod { link ->
                    val info = extractPixivId(link)
                    if (info.type == "novels") {
                        info.value.toLongOrNull()?.let { novelId ->
                            binding.caption.findActionReceiverOrNull<NovelActionReceiver>()?.visitNovelById(novelId)
                        }
                    } else if (info.type == "illusts") {
                        info.value.toLongOrNull()?.let { novelId ->
                            binding.caption.findActionReceiverOrNull<IllustCardActionReceiver>()?.visitIllustById(novelId)
                        }
                    }
                    Timber.d("sdasdwq2 ${info}")
                }
                Timber.d("novelCaption: ${novel.caption}")
                binding.caption.text = HtmlCompat.fromHtml(novel.caption, HtmlCompat.FROM_HTML_MODE_COMPACT)
            } else {
                binding.caption.isVisible = false
            }
            binding.userId.setOnClick {
                Common.copy(context, novel.user?.id?.toString())
            }
            binding.publishTime.text = context.getString(
                R.string.published_on,
                DateParse.getTimeAgo(context, novel.create_date)
            )
        }
        binding.illustId.setOnClick {
            Common.copy(context, holder.novelId.toString())
        }
    }
}
