package ceui.lisa.fragments

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.activities.UActivity
import ceui.lisa.activities.VActivity
import ceui.lisa.core.Container
import ceui.lisa.core.PageData
import ceui.lisa.database.IllustHistoryEntity
import ceui.lisa.models.IllustsBean
import ceui.lisa.models.NovelBean
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import com.bumptech.glide.Glide
import java.text.SimpleDateFormat
import java.util.Locale

class HistoryV3Adapter(
    private val context: Context,
    private val items: MutableList<IllustHistoryEntity>,
    private val allIllustsProvider: () -> List<IllustsBean>,
    private val onRequestDelete: (position: Int, entity: IllustHistoryEntity) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val timeFormat by lazy {
        SimpleDateFormat(
            context.getString(R.string.string_350),
            Locale.getDefault()
        )
    }

    override fun getItemViewType(position: Int): Int = items[position].type

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_NOVEL) {
            NovelVH(inflater.inflate(R.layout.cell_history_novel_v3, parent, false))
        } else {
            IllustVH(inflater.inflate(R.layout.cell_history_illust_v3, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val entity = items[position]
        when (holder) {
            is IllustVH -> holder.bind(entity)
            is NovelVH -> holder.bind(entity)
        }
        val lp = holder.itemView.layoutParams
        if (lp is StaggeredGridLayoutManager.LayoutParams) {
            lp.isFullSpan = entity.type == TYPE_NOVEL
        }
    }

    override fun getItemCount(): Int = items.size

    fun removeAt(position: Int) {
        if (position in items.indices) {
            items.removeAt(position)
            notifyItemRemoved(position)
            notifyItemRangeChanged(position, items.size - position)
        }
    }

    fun clear() {
        val oldSize = items.size
        items.clear()
        notifyItemRangeRemoved(0, oldSize)
    }

    fun submit(newItems: List<IllustHistoryEntity>) {
        val oldSize = items.size
        items.clear()
        items.addAll(newItems)
        if (oldSize == 0) {
            notifyItemRangeInserted(0, items.size)
        } else {
            notifyDataSetChanged()
        }
    }

    fun append(more: List<IllustHistoryEntity>) {
        if (more.isEmpty()) return
        val start = items.size
        items.addAll(more)
        notifyItemRangeInserted(start, more.size)
    }

    private inner class IllustVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val image: ImageView = itemView.findViewById(R.id.illust_image)
        private val title: TextView = itemView.findViewById(R.id.title)
        private val author: TextView = itemView.findViewById(R.id.author)
        private val time: TextView = itemView.findViewById(R.id.time)
        private val pSize: TextView = itemView.findViewById(R.id.p_size)
        private val delete: ImageView = itemView.findViewById(R.id.delete_item)

        fun bind(entity: IllustHistoryEntity) {
            val illust = Shaft.sGson.fromJson(entity.illustJson, IllustsBean::class.java) ?: return
            val width = (context.resources.displayMetrics.widthPixels -
                    context.resources.getDimensionPixelSize(R.dimen.four_dp) * 6) / 2
            val imageHeight = if (illust.width > 0) {
                (width.toFloat() * illust.height / illust.width).toInt().coerceAtLeast(width / 2)
            } else width

            image.layoutParams = image.layoutParams.apply {
                this.width = width
                this.height = imageHeight
            }
            Glide.with(context)
                .load(GlideUtil.getMediumImg(illust))
                .placeholder(R.color.v3_surface_2)
                .into(image)
            title.text = illust.title
            author.text = "${illust.user?.name ?: ""}"
            time.text = timeFormat.format(entity.time)

            when {
                illust.isGif -> {
                    pSize.isVisible = true
                    pSize.text = "GIF"
                }
                illust.page_count > 1 -> {
                    pSize.isVisible = true
                    pSize.text = String.format(Locale.getDefault(), "%dP", illust.page_count)
                }
                else -> pSize.isVisible = false
            }

            itemView.setOnClickListener { openIllust(illust) }
            itemView.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onRequestDelete(pos, entity)
                true
            }
            delete.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onRequestDelete(pos, entity)
            }
            author.setOnClickListener {
                illust.user?.id?.let { openUser(it) }
            }
        }
    }

    private inner class NovelVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val image: ImageView = itemView.findViewById(R.id.illust_image)
        private val title: TextView = itemView.findViewById(R.id.title)
        private val author: TextView = itemView.findViewById(R.id.author)
        private val time: TextView = itemView.findViewById(R.id.time)
        private val delete: ImageView = itemView.findViewById(R.id.delete_item)

        fun bind(entity: IllustHistoryEntity) {
            val novel = Shaft.sGson.fromJson(entity.illustJson, NovelBean::class.java) ?: return
            Glide.with(context)
                .load(GlideUtil.getUrl(novel.image_urls?.medium))
                .placeholder(R.color.v3_surface_2)
                .into(image)
            title.text = novel.title
            author.text = "${novel.user?.name ?: ""}"
            time.text = timeFormat.format(entity.time)

            itemView.setOnClickListener { openNovel(novel) }
            itemView.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onRequestDelete(pos, entity)
                true
            }
            delete.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onRequestDelete(pos, entity)
            }
            author.setOnClickListener {
                novel.user?.id?.let { openUser(it) }
            }
        }
    }

    private fun openIllust(illust: IllustsBean) {
        val all = allIllustsProvider()
        if (all.isEmpty()) return
        val pageData = PageData(all)
        Container.get().addPageToMap(pageData)
        val index = all.indexOfFirst { it.id == illust.id }.coerceAtLeast(0)
        val intent = Intent(context, VActivity::class.java).apply {
            putExtra(Params.POSITION, index)
            putExtra(Params.PAGE_UUID, pageData.getUUID())
        }
        context.startActivity(intent)
    }

    private fun openNovel(novel: NovelBean) {
        val intent = Intent(context, TemplateActivity::class.java).apply {
            putExtra(Params.CONTENT, novel)
            putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说详情")
            putExtra("hideStatusBar", true)
        }
        context.startActivity(intent)
    }

    private fun openUser(userId: Int) {
        val intent = Intent(context, UActivity::class.java).apply {
            putExtra(Params.USER_ID, userId)
        }
        context.startActivity(intent)
    }

    companion object {
        private const val TYPE_ILLUST = 0
        private const val TYPE_NOVEL = 1
    }
}
