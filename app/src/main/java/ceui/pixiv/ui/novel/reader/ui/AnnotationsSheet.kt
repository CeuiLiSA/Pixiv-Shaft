package ceui.pixiv.ui.novel.reader.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.database.NovelAnnotationEntity
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Lists the saved highlights / notes for the current novel. Callback-driven so
 * the Fragment can jump to an entry, edit a note, or delete it without this
 * class reaching into the ViewModel.
 */
class AnnotationsSheet : BottomSheetDialogFragment() {

    private var entries: List<NovelAnnotationEntity> = emptyList()
    private var onJumpTo: ((NovelAnnotationEntity) -> Unit)? = null
    private var onEdit: ((NovelAnnotationEntity) -> Unit)? = null
    private var onDelete: ((NovelAnnotationEntity) -> Unit)? = null

    fun configure(
        entries: List<NovelAnnotationEntity>,
        onJumpTo: (NovelAnnotationEntity) -> Unit,
        onEdit: (NovelAnnotationEntity) -> Unit,
        onDelete: (NovelAnnotationEntity) -> Unit,
    ) {
        this.entries = entries
        this.onJumpTo = onJumpTo
        this.onEdit = onEdit
        this.onDelete = onDelete
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(0, (12 * density).toInt(), 0, (8 * density).toInt())
        }
        root.addView(handleIndicator(ctx))

        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * density).toInt(), 0, (16 * density).toInt(), (8 * density).toInt())
        }
        val title = TextView(ctx).apply {
            text = "笔记 / 高亮"
            setTextColor(Color.BLACK)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
        }
        val count = TextView(ctx).apply {
            text = "${entries.size} 条"
            setTextColor(0xFF888888.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        titleRow.addView(title)
        titleRow.addView(count)
        root.addView(titleRow)

        val divider = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(0x1F000000)
        }
        root.addView(divider)

        if (entries.isEmpty()) {
            val empty = TextView(ctx).apply {
                text = "还没有笔记或高亮。\n长按文字 → 选择颜色即可保存。"
                gravity = Gravity.CENTER
                setTextColor(0xFF888888.toInt())
                setPadding(0, (48 * density).toInt(), 0, (48 * density).toInt())
            }
            root.addView(empty)
        } else {
            val list = RecyclerView(ctx).apply {
                layoutManager = LinearLayoutManager(ctx)
                adapter = Adapter(entries, onJumpTo, onEdit, onDelete) { dismissAllowingStateLoss() }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            root.addView(list)
        }

        return root
    }

    private fun handleIndicator(ctx: android.content.Context): View {
        val density = ctx.resources.displayMetrics.density
        val holder = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (18 * density).toInt())
        }
        val indicator = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 2 * density
                setColor(0x33000000)
            }
            layoutParams = FrameLayout.LayoutParams((40 * density).toInt(), (4 * density).toInt(), Gravity.CENTER)
        }
        holder.addView(indicator)
        return holder
    }

    private class Adapter(
        private val entries: List<NovelAnnotationEntity>,
        private val onJumpTo: ((NovelAnnotationEntity) -> Unit)?,
        private val onEdit: ((NovelAnnotationEntity) -> Unit)?,
        private val onDelete: ((NovelAnnotationEntity) -> Unit)?,
        private val dismiss: () -> Unit,
    ) : RecyclerView.Adapter<Adapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val density = ctx.resources.displayMetrics.density
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
                applySelectableBackground()
            }
            val colorChip = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams((6 * density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    marginEnd = (12 * density).toInt()
                }
            }
            val content = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
            }
            val excerpt = TextView(ctx).apply {
                setTextColor(0xFF333333.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val note = TextView(ctx).apply {
                setTextColor(0xFF5B6EFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, (4 * density).toInt(), 0, 0)
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val footer = TextView(ctx).apply {
                setTextColor(0xFFAAAAAA.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(0, (6 * density).toInt(), 0, 0)
            }
            content.addView(excerpt)
            content.addView(note)
            content.addView(footer)
            row.addView(colorChip)
            row.addView(content)
            return VH(row, colorChip, excerpt, note, footer)
        }

        override fun getItemCount(): Int = entries.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = entries[position]
            holder.colorChip.setBackgroundColor(entry.color)
            holder.excerpt.text = "「${entry.excerpt}」"
            if (entry.note.isNotEmpty()) {
                holder.note.visibility = View.VISIBLE
                holder.note.text = "📝 ${entry.note}"
            } else {
                holder.note.visibility = View.GONE
            }
            val ctx = holder.itemView.context
            holder.footer.text = DateFormat.format("yyyy-MM-dd HH:mm", entry.updatedTime).toString()
            holder.itemView.setOnClickListener {
                onJumpTo?.invoke(entry)
                dismiss()
            }
            holder.itemView.setOnLongClickListener {
                val options = arrayOf(if (entry.note.isEmpty()) "添加笔记" else "编辑笔记", "删除")
                androidx.appcompat.app.AlertDialog.Builder(ctx)
                    .setTitle(entry.excerpt.take(24))
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> onEdit?.invoke(entry)
                            1 -> onDelete?.invoke(entry)
                        }
                    }
                    .show()
                true
            }
        }

        class VH(
            itemView: View,
            val colorChip: View,
            val excerpt: TextView,
            val note: TextView,
            val footer: TextView,
        ) : RecyclerView.ViewHolder(itemView)
    }

    companion object {
        const val TAG = "AnnotationsSheet"
    }
}
