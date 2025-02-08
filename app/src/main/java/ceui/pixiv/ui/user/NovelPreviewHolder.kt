package ceui.pixiv.ui.user

import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellSectionPreviewBinding
import ceui.loxia.IllustResponse
import ceui.loxia.NovelResponse
import ceui.loxia.RefreshHint
import ceui.loxia.SpaceHolder
import ceui.loxia.setUpHolderRefreshState
import ceui.pixiv.ui.chats.IllustSquareV2Holder
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.ui.common.NovelCardHolder
import ceui.pixiv.ui.common.ValueContent
import ceui.pixiv.utils.ppppx


class NovelPreviewHolder(val valueContent: ValueContent<NovelResponse>, val previewCount: Int) : ListItemHolder() {
}

@ItemHolder(NovelPreviewHolder::class)
class NovelPreviewViewHolder(private val bd: CellSectionPreviewBinding) :
    ListItemViewHolder<CellSectionPreviewBinding, NovelPreviewHolder>(bd) {

    override fun onBindViewHolder(holder: NovelPreviewHolder, position: Int) {
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
        binding.previewListView.layoutManager = LinearLayoutManager(context)
        holder.valueContent.result.observe(lifecycleOwner) { resp ->
            val limitedList = resp.displayList.take(holder.previewCount)
            adapter.submitList(limitedList.flatMap { novel ->
                listOf(NovelCardHolder(novel), SpaceHolder(6.ppppx))
            })
        }
    }
}