package ceui.pixiv.ui.user

import androidx.recyclerview.widget.GridLayoutManager
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellSectionPreviewBinding
import ceui.loxia.IllustResponse
import ceui.loxia.RefreshHint
import ceui.loxia.clearItemDecorations
import ceui.loxia.setUpHolderRefreshState
import ceui.pixiv.ui.chats.GridItemDecoration
import ceui.pixiv.ui.chats.IllustSquareHolder
import ceui.pixiv.ui.chats.IllustSquareV2Holder
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.ui.common.ValueContent
import ceui.pixiv.utils.ppppx


class SectionPreviewHolder(val valueContent: ValueContent<IllustResponse>, val previewCount: Int) : ListItemHolder() {
}

@ItemHolder(SectionPreviewHolder::class)
class SectionPreviewViewHolder(private val bd: CellSectionPreviewBinding) :
    ListItemViewHolder<CellSectionPreviewBinding, SectionPreviewHolder>(bd) {

    override fun onBindViewHolder(holder: SectionPreviewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.itemLoadingLayout.setUpHolderRefreshState(
            holder.valueContent.refreshState,
            lifecycleOwner
        ) {
            holder.valueContent.refresh(
                RefreshHint.ErrorRetry
            )
        }
        val adapter = CommonAdapter(lifecycleOwner)
        binding.previewListView.adapter = adapter
        binding.previewListView.layoutManager = GridLayoutManager(context, 3)
        binding.previewListView.clearItemDecorations()
        binding.previewListView.addItemDecoration(GridItemDecoration(3, 4.ppppx, false))
        holder.valueContent.result.observe(lifecycleOwner) { resp ->
            val limitedList = resp.displayList.take(holder.previewCount)
            adapter.submitList(limitedList.map { illust -> IllustSquareV2Holder(illust) })
        }
    }
}