package ceui.pixiv.ui.novel

import android.content.Intent
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.ItemBigReadButtonBinding
import ceui.lisa.utils.Params
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.utils.setOnClick

class ReadNovelButtonHolder(val novelId: Long) : ListItemHolder() {
    override fun getItemId(): Long = novelId

    override fun areItemsTheSame(other: ListItemHolder): Boolean =
        (other as? ReadNovelButtonHolder)?.novelId == novelId

    override fun areContentsTheSame(other: ListItemHolder): Boolean =
        (other as? ReadNovelButtonHolder)?.novelId == novelId
}

@ItemHolder(ReadNovelButtonHolder::class)
class ReadNovelButtonViewHolder(bd: ItemBigReadButtonBinding) :
    ListItemViewHolder<ItemBigReadButtonBinding, ReadNovelButtonHolder>(bd) {

    override fun onBindViewHolder(holder: ReadNovelButtonHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.btnRead.setOnClick {
            val ctx = it.context
            val intent = Intent(ctx, TemplateActivity::class.java).apply {
                putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说正文")
                putExtra(Params.NOVEL_ID, holder.novelId)
            }
            ctx.startActivity(intent)
        }
    }
}
