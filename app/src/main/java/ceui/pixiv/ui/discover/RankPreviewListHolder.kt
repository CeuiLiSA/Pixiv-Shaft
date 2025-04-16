package ceui.pixiv.ui.discover

import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellRankPreviewListBinding
import ceui.lisa.view.LinearItemHorizontalJustLRDecoration
import ceui.loxia.Illust
import ceui.loxia.ThumbnailItem
import ceui.loxia.clearItemDecorations
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.ui.rank.ArticlePreviewHolder
import ceui.pixiv.ui.rank.RankPreviewHolder
import ceui.pixiv.utils.ppppx


class RankPreviewListHolder(title: String, list: List<Illust>) :
    GenericPreviewListHolder<Illust>(title, list)

@ItemHolder(RankPreviewListHolder::class)
class RankPreviewListViewHolder(bd: CellRankPreviewListBinding) :
    GenericPreviewListViewHolder<Illust>(
        bd,
        mapItem = { RankPreviewHolder(it) }
    )


class ArticlePreviewListHolder(title: String, list: List<ThumbnailItem>) :
    GenericPreviewListHolder<ThumbnailItem>(title, list)


@ItemHolder(ArticlePreviewListHolder::class)
class ArticlePreviewListViewHolder(bd: CellRankPreviewListBinding) :
    GenericPreviewListViewHolder<ThumbnailItem>(
        bd,
        mapItem = { ArticlePreviewHolder(it) }
    )

open class GenericPreviewListHolder<T>(
    val title: String,
    val list: List<T>
) : ListItemHolder()

open class GenericPreviewListViewHolder<T>(
    private val bd: CellRankPreviewListBinding,
    private val mapItem: (T) -> ListItemHolder
) : ListItemViewHolder<CellRankPreviewListBinding, GenericPreviewListHolder<T>>(bd) {

    override fun onBindViewHolder(holder: GenericPreviewListHolder<T>, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.contentText.text = holder.title

        val rankingAdapter = CommonAdapter(lifecycleOwner)
        binding.rankIllustList.adapter = rankingAdapter
        binding.rankIllustList.clearItemDecorations()
        binding.rankIllustList.addItemDecoration(LinearItemHorizontalJustLRDecoration(4.ppppx))
        binding.rankIllustList.layoutManager =
            LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)

        rankingAdapter.submitList(holder.list.map(mapItem))
    }
}