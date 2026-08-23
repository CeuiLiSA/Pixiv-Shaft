@file:JvmName("LegacyTagConverter")

package ceui.loxia

import ceui.lisa.models.TagsBean

/** loxia [Tag] → legacy [TagsBean]（TagAdapter / 屏蔽标签等旧入参仍使用 TagsBean）。 */
fun Tag.toTagsBean(): TagsBean = TagsBean().also {
    it.name = name
    it.translated_name = translated_name
}

fun List<Tag>.toTagsBeans(): List<TagsBean> = map { it.toTagsBean() }
