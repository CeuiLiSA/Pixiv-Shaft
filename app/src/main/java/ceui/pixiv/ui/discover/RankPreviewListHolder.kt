package ceui.pixiv.ui.discover

import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellRankPreviewListBinding
import ceui.lisa.view.LinearItemHorizontalJustLRDecoration
import ceui.loxia.Illust
import ceui.loxia.clearItemDecorations
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.ui.rank.RankPreviewHolder
import ceui.pixiv.utils.ppppx

class RankPreviewListHolder(val title: String, val list: List<Illust>) : ListItemHolder() {}

@ItemHolder(RankPreviewListHolder::class)
class RankPreviewListViewHolder(private val bd: CellRankPreviewListBinding) :
    ListItemViewHolder<CellRankPreviewListBinding, RankPreviewListHolder>(bd) {

    override fun onBindViewHolder(holder: RankPreviewListHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.contentText.text = holder.title

        val rankingAdapter = CommonAdapter(lifecycleOwner)
        binding.rankIllustList.adapter = rankingAdapter
        binding.rankIllustList.clearItemDecorations()
        binding.rankIllustList.addItemDecoration(LinearItemHorizontalJustLRDecoration(4.ppppx))
        binding.rankIllustList.layoutManager =
            LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        rankingAdapter.submitList(holder.list.map { RankPreviewHolder(it) })
    }
}

