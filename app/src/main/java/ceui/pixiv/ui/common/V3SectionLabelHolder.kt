package ceui.pixiv.ui.common

import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.ItemV3SectionLabelBinding

/**
 * V3 风格的区域标题标签：大写、12sp、v3_text_3 色、letterSpacing 0.12。
 * 用于替代旧式 RedSectionHeaderHolder，视觉对齐 V3 详情页的 section header。
 */
class V3SectionLabelHolder(
    val title: String,
) : ListItemHolder()

@ItemHolder(V3SectionLabelHolder::class)
class V3SectionLabelViewHolder(bd: ItemV3SectionLabelBinding) :
    ListItemViewHolder<ItemV3SectionLabelBinding, V3SectionLabelHolder>(bd) {

    override fun onBindViewHolder(holder: V3SectionLabelHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.label.text = holder.title
    }
}
