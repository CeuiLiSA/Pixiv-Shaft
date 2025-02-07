package ceui.pixiv.ui.user

import androidx.core.text.HtmlCompat
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellUserLinkBinding
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder

class XLinkHolder(val text: String?) : ListItemHolder() {
    override fun getItemId(): Long {
        return text.hashCode().toLong()
    }
}

@ItemHolder(XLinkHolder::class)
class XLinkViewHolder(bd: CellUserLinkBinding) : ListItemViewHolder<CellUserLinkBinding, XLinkHolder>(bd) {

    override fun onBindViewHolder(holder: XLinkHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.contentText.text = HtmlCompat.fromHtml(holder.text ?: "", HtmlCompat.FROM_HTML_MODE_COMPACT)
    }
}