package ceui.pixiv.ui.rank

import androidx.core.view.updateLayoutParams
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.ItemArticlePreviewBinding
import ceui.lisa.databinding.ItemIllustSquareBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.loxia.Illust
import ceui.loxia.ThumbnailItem
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.IllustCardActionReceiver
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide



class ArticlePreviewHolder(val thumbnailItem: ThumbnailItem) : ListItemHolder() {

    override fun getItemId(): Long {
        return thumbnailItem.image_url?.hashCode()?.toLong() ?: 0L
    }
}


@ItemHolder(ArticlePreviewHolder::class)
class ArticlePreviewViewHolder(aa: ItemArticlePreviewBinding) :
    ListItemViewHolder<ItemArticlePreviewBinding, ArticlePreviewHolder>(aa) {

    override fun onBindViewHolder(holder: ArticlePreviewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.holder = holder
        val w = 260.ppppx
        binding.root.updateLayoutParams {
            width = w
        }
    }
}