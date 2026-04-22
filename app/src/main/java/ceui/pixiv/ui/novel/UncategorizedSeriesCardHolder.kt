package ceui.pixiv.ui.novel

import android.content.Intent
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellUncategorizedNovelsCardBinding
import ceui.lisa.utils.Params
import ceui.lisa.utils.V3Palette
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.utils.setOnClick

/**
 * 作者小说 tab 顶部的「未归类作品」虚拟系列卡片。Pixiv 里很多作者的独立单篇不归入任何
 * 系列（`novel.series == null`），之前没有一个入口能一次性看齐/批量下载这些独立作品。
 * 这张卡做的就是把它们聚合成一个「虚拟系列」，点击进入专门的多选+批量下载页面。
 *
 * [count] 是一个 LiveData：外层 Fragment 在后台分页把所有作品拉下来、过滤出没有 series
 * 的那些、把数量回灌进来。加载过程中显示「正在统计独立单篇…」，完成后切换成具体数字。
 * 如果最终数量是 0，外层负责把这个 holder 从列表里拿掉，避免误导用户。
 */
class UncategorizedSeriesCardHolder(
    val userId: Long,
    val count: LiveData<Int?> = MutableLiveData(null),
) : ListItemHolder() {

    override fun getItemId(): Long = -userId // stable, distinct from any novel id

    override fun areItemsTheSame(other: ListItemHolder): Boolean {
        return (other as? UncategorizedSeriesCardHolder)?.userId == userId
    }

    override fun areContentsTheSame(other: ListItemHolder): Boolean {
        // Count is observed directly via LiveData in the view holder, so
        // changing counts don't need to force a full rebind; just match the
        // identity.
        return areItemsTheSame(other)
    }
}

@ItemHolder(UncategorizedSeriesCardHolder::class)
class UncategorizedSeriesCardViewHolder(bd: CellUncategorizedNovelsCardBinding) :
    ListItemViewHolder<CellUncategorizedNovelsCardBinding, UncategorizedSeriesCardHolder>(bd) {

    override fun onBindViewHolder(holder: UncategorizedSeriesCardHolder, position: Int) {
        super.onBindViewHolder(holder, position)

        val palette = V3Palette.from(context)
        // Accent the book icon with the current theme's primary so the card
        // feels like a first-class series entry rather than a plain menu row.
        binding.icon.setColorFilter(palette.textAccent)

        holder.count.observe(lifecycleOwner) { c ->
            binding.subtitle.text = if (c == null) {
                context.getString(R.string.uncategorized_novels_loading)
            } else {
                context.getString(R.string.uncategorized_novels_count, c)
            }
        }

        binding.cardRoot.setOnClick {
            val intent = Intent(context, TemplateActivity::class.java).apply {
                putExtra(TemplateActivity.EXTRA_FRAGMENT, "未归类小说")
                putExtra(Params.USER_ID, holder.userId.toInt())
            }
            context.startActivity(intent)
        }
    }
}
