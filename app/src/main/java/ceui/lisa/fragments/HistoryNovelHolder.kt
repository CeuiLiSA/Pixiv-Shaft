package ceui.lisa.fragments

import android.content.Intent
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.activities.UActivity
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.database.IllustHistoryEntity
import ceui.lisa.databinding.CellHistoryNovelV3Binding
import ceui.lisa.models.NovelBean
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import com.bumptech.glide.Glide
import java.text.SimpleDateFormat
import java.util.Locale

class HistoryNovelHolder(
    val entity: IllustHistoryEntity,
    val novel: NovelBean,
    val onRequestDelete: (IllustHistoryEntity) -> Unit,
) : ListItemHolder() {

    override fun getItemId(): Long = entity.illustID.toLong()

    override fun areContentsTheSame(other: ListItemHolder): Boolean {
        return other is HistoryNovelHolder && other.entity.time == entity.time
    }
}

@ItemHolder(HistoryNovelHolder::class)
class HistoryNovelViewHolder(bd: CellHistoryNovelV3Binding) :
    ListItemViewHolder<CellHistoryNovelV3Binding, HistoryNovelHolder>(bd) {

    private val timeFormat by lazy {
        SimpleDateFormat(context.getString(R.string.string_350), Locale.getDefault())
    }

    override fun onBindViewHolder(holder: HistoryNovelHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val novel = holder.novel
        val entity = holder.entity

        binding.pSize.visibility = android.view.View.GONE
        Glide.with(context).load(GlideUtil.getUrl(novel.image_urls?.medium))
            .placeholder(R.color.v3_surface_2).into(binding.illustImage)
        binding.title.text = novel.title
        binding.author.text = novel.user?.name.orEmpty()
        binding.time.text = timeFormat.format(entity.time)

        binding.root.setOnClickListener {
            context.startActivity(Intent(context, TemplateActivity::class.java).apply {
                putExtra(Params.CONTENT, novel)
                putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说详情")
                putExtra("hideStatusBar", true)
            })
        }
        binding.root.setOnLongClickListener {
            holder.onRequestDelete(entity)
            true
        }
        binding.deleteItem.setOnClickListener {
            holder.onRequestDelete(entity)
        }
        binding.author.setOnClickListener {
            novel.user?.id?.let { uid ->
                context.startActivity(Intent(context, UActivity::class.java).apply {
                    putExtra(Params.USER_ID, uid)
                })
            }
        }
    }
}
