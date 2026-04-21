package ceui.lisa.fragments

import android.content.Intent
import android.view.View
import androidx.core.view.isVisible
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.UActivity
import ceui.lisa.activities.VActivity
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.core.Container
import ceui.lisa.core.PageData
import ceui.lisa.database.IllustHistoryEntity
import ceui.lisa.databinding.CellHistoryIllustV3Binding
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.loxia.ObjectPool
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import com.bumptech.glide.Glide
import java.text.SimpleDateFormat
import java.util.Locale

class HistoryIllustHolder(
    val entity: IllustHistoryEntity,
    val illust: IllustsBean,
    val allIllustsProvider: () -> List<IllustsBean>,
    val onRequestDelete: (IllustHistoryEntity) -> Unit,
) : ListItemHolder() {

    override fun getItemId(): Long = entity.illustID.toLong()

    override fun areContentsTheSame(other: ListItemHolder): Boolean {
        return other is HistoryIllustHolder && other.entity.time == entity.time
    }
}

@ItemHolder(HistoryIllustHolder::class)
class HistoryIllustViewHolder(bd: CellHistoryIllustV3Binding) :
    ListItemViewHolder<CellHistoryIllustV3Binding, HistoryIllustHolder>(bd) {

    private val timeFormat by lazy {
        SimpleDateFormat(context.getString(R.string.string_350), Locale.getDefault())
    }

    override fun onBindViewHolder(holder: HistoryIllustHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val illust = holder.illust
        val entity = holder.entity

        val screenWidth = context.resources.displayMetrics.widthPixels
        val itemWidth = (screenWidth - context.resources.getDimensionPixelSize(R.dimen.four_dp) * 6) / 2
        val imageHeight = if (illust.width > 0) {
            (itemWidth.toFloat() * illust.height / illust.width).toInt().coerceAtLeast(itemWidth / 2)
        } else itemWidth

        binding.illustImage.layoutParams = binding.illustImage.layoutParams.apply {
            width = itemWidth
            height = imageHeight
        }
        Glide.with(context).load(GlideUtil.getMediumImg(illust))
            .placeholder(R.color.v3_surface_2).into(binding.illustImage)
        binding.title.text = illust.title
        binding.author.text = illust.user?.name.orEmpty()
        binding.time.text = timeFormat.format(entity.time)

        when {
            illust.isGif -> { binding.pSize.isVisible = true; binding.pSize.text = "GIF" }
            illust.page_count > 1 -> {
                binding.pSize.isVisible = true
                binding.pSize.text = String.format(Locale.getDefault(), "%dP", illust.page_count)
            }
            else -> binding.pSize.isVisible = false
        }

        binding.root.setOnClickListener {
            val all = holder.allIllustsProvider()
            if (all.isEmpty()) return@setOnClickListener
            val pageData = PageData(all)
            Container.get().addPageToMap(pageData)
            val index = all.indexOfFirst { it.id == illust.id }.coerceAtLeast(0)
            context.startActivity(Intent(context, VActivity::class.java).apply {
                putExtra(Params.POSITION, index)
                putExtra(Params.PAGE_UUID, pageData.uuid)
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
            illust.user?.id?.let { uid ->
                context.startActivity(Intent(context, UActivity::class.java).apply {
                    putExtra(Params.USER_ID, uid)
                })
            }
        }
    }
}
