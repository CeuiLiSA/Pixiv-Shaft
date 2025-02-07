package ceui.pixiv.ui.user

import androidx.core.text.HtmlCompat
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellUserTextBinding
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder



class UserTextHolder(val text: String?) : ListItemHolder() {
    override fun getItemId(): Long {
        return text.hashCode().toLong()
    }
}

@ItemHolder(UserTextHolder::class)
class UserTextiewHolder(bd: CellUserTextBinding) : ListItemViewHolder<CellUserTextBinding, UserTextHolder>(bd) {

    override fun onBindViewHolder(holder: UserTextHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.contentText.text = HtmlCompat.fromHtml(holder.text ?: "", HtmlCompat.FROM_HTML_MODE_COMPACT)
    }
}