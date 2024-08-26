package ceui.pixiv.ui.common

import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellNovelCardBinding
import ceui.lisa.databinding.CellPvisionCardBinding
import ceui.lisa.databinding.CellPvisionMiniCardBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.loxia.Article
import ceui.loxia.Novel
import com.bumptech.glide.Glide

class PvisionCardHolder(val article: Article) : ListItemHolder() {

    override fun areItemsTheSame(other: ListItemHolder): Boolean {
        return article.id == (other as? PvisionCardHolder)?.article?.id
    }

    override fun areContentsTheSame(other: ListItemHolder): Boolean {
        return article == (other as? PvisionCardHolder)?.article
    }
}

@ItemHolder(PvisionCardHolder::class)
class PvisionCardViewHolder(bd: CellPvisionCardBinding) : ListItemViewHolder<CellPvisionCardBinding, PvisionCardHolder>(bd) {

    override fun onBindViewHolder(holder: PvisionCardHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.holder = holder
    }
}