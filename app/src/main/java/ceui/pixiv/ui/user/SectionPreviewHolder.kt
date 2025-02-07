package ceui.pixiv.ui.user

import androidx.recyclerview.widget.GridLayoutManager
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellSectionPreviewBinding
import ceui.loxia.IllustResponse
import ceui.pixiv.ui.chats.IllustSquareHolder
import ceui.pixiv.ui.chats.IllustSquareV2Holder
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.ui.common.ValueContent


class SectionPreviewHolder(val valueContent: ValueContent<IllustResponse>) : ListItemHolder() {
}

@ItemHolder(SectionPreviewHolder::class)
class SectionPreviewViewHolder(private val bd: CellSectionPreviewBinding) :
    ListItemViewHolder<CellSectionPreviewBinding, SectionPreviewHolder>(bd) {

    override fun onBindViewHolder(holder: SectionPreviewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val adapter = CommonAdapter(lifecycleOwner)
        binding.previewListView.adapter = adapter
        binding.previewListView.layoutManager = GridLayoutManager(context, 3)
        holder.valueContent.result.observe(lifecycleOwner) { resp ->
            val limitedList = resp.displayList.take(9)
            adapter.submitList(limitedList.map { illust -> IllustSquareV2Holder(illust) })
        }
    }
}