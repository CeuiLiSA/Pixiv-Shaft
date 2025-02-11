package ceui.pixiv.ui.detail

import android.text.method.LinkMovementMethod
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import ceui.lisa.R
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellArtworkCaptionBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.ShareIllust
import ceui.loxia.DateParse
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.ui.novel.CustomLinkMovementMethod
import ceui.pixiv.utils.setOnClick
import timber.log.Timber


class ArtworkCaptionHolder(val illustId: Long) : ListItemHolder() {
    override fun getItemId(): Long {
        return illustId
    }
}

@ItemHolder(ArtworkCaptionHolder::class)
class ArtworkCaptionViewHolder(bd: CellArtworkCaptionBinding) : ListItemViewHolder<CellArtworkCaptionBinding, ArtworkCaptionHolder>(bd) {

    override fun onBindViewHolder(holder: ArtworkCaptionHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val liveIllust = ObjectPool.get<Illust>(holder.illustId)
        binding.illust = liveIllust
        liveIllust.observe(lifecycleOwner) { illust ->
            if (illust.caption?.isNotEmpty() == true) {
                binding.caption.isVisible = true
                binding.caption.text = HtmlCompat.fromHtml(illust.caption, HtmlCompat.FROM_HTML_MODE_COMPACT)
            } else {
                binding.caption.isVisible = false
            }
            binding.userId.setOnClick {
                Common.copy(context, illust.user?.id?.toString())
            }
            binding.illustLink.text =
                context.getString(R.string.artwork_link, ShareIllust.URL_Head + illust.id)
            binding.illustLink.setOnClick {
                Common.copy(context, ShareIllust.URL_Head + illust.id)
            }

            binding.userLink.text =
                context.getString(R.string.user_link, ShareIllust.USER_URL_Head + illust.user?.id)
            binding.userLink.setOnClick {
                Common.copy(context, ShareIllust.USER_URL_Head + illust.user?.id)
            }

            binding.publishTime.text = context.getString(
                R.string.published_on,
                DateParse.getTimeAgo(context, illust.create_date)
            )
        }
        binding.illustId.setOnClick {
            Common.copy(context, holder.illustId.toString())
        }
    }
}
