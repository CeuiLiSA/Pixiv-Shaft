package ceui.pixiv.paging

import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.SampleTextBinding
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder

class PagingItemHolder(val pagingArticle: PagingArticle) : ListItemHolder() {

    override fun getItemId(): Long {
        return pagingArticle.id.toLong()
    }
}

@ItemHolder(PagingItemHolder::class)
class PagingItemViewHolder(bd: SampleTextBinding) :
    ListItemViewHolder<SampleTextBinding, PagingItemHolder>(bd) {

    override fun onBindViewHolder(holder: PagingItemHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        
        binding.textView.text = holder.pagingArticle.title
    }
}