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
import ceui.lisa.database.NovelBookmarkEntity
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Lists user-placed position bookmarks ("save my spot here"). Click jumps to
 * that page; long-press deletes.
 */
class BookmarksSheet : BottomSheetDialogFragment() {

    private var entries: List<NovelBookmarkEntity> = emptyList()
    private var onJumpTo: ((NovelBookmarkEntity) -> Unit)? = null
    private var onDelete: ((NovelBookmarkEntity) -> Unit)? = null

    fun configure(
        entries: List<NovelBookmarkEntity>,
        onJumpTo: (NovelBookmarkEntity) -> Unit,
        onDelete: (NovelBookmarkEntity) -> Unit,
    ) {
        this.entries = entries
        this.onJumpTo = onJumpTo
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

        val handle = FrameLayout(ctx).apply {
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
        handle.addView(indicator)
        root.addView(handle)

        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * density).toInt(), 0, (16 * density).toInt(), (8 * density).toInt())
        }
        val title = TextView(ctx).apply {
            text = getString(ceui.lisa.R.string.bookmarks_title)
            setTextColor(Color.BLACK)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
        }
        val count = TextView(ctx).apply {
            text = getString(ceui.lisa.R.string.bookmarks_count, entries.size)
            setTextColor(0xFF888888.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        titleRow.addView(title)
        titleRow.addView(count)
        root.addView(titleRow)

        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(0x1F000000)
        })

        if (entries.isEmpty()) {
            root.addView(TextView(ctx).apply {
                text = getString(ceui.lisa.R.string.bookmarks_empty)
                gravity = Gravity.CENTER
                setTextColor(0xFF888888.toInt())
                setPadding(0, (48 * density).toInt(), 0, (48 * density).toInt())
            })
        } else {
            val list = RecyclerView(ctx).apply {
                layoutManager = LinearLayoutManager(ctx)
                adapter = Adapter(entries, onJumpTo, onDelete) { dismissAllowingStateLoss() }
            }
            root.addView(list)
        }
        return root
    }

    private class Adapter(
        private val entries: List<NovelBookmarkEntity>,
        private val onJumpTo: ((NovelBookmarkEntity) -> Unit)?,
        private val onDelete: ((NovelBookmarkEntity) -> Unit)?,
        private val dismiss: () -> Unit,
    ) : RecyclerView.Adapter<Adapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val density = ctx.resources.displayMetrics.density
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
                applySelectableBackground()
            }
            val preview = TextView(ctx).apply {
                setTextColor(0xFF333333.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val footer = TextView(ctx).apply {
                setTextColor(0xFFAAAAAA.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(0, (6 * density).toInt(), 0, 0)
            }
            row.addView(preview)
            row.addView(footer)
            return VH(row, preview, footer)
        }

        override fun getItemCount(): Int = entries.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = entries[position]
            val ctx = holder.itemView.context
            holder.preview.text = if (entry.preview.isNotEmpty()) entry.preview else ctx.getString(ceui.lisa.R.string.bookmarks_page_format, entry.pageIndex + 1)
            holder.footer.text = "${ctx.getString(ceui.lisa.R.string.bookmarks_page_format, entry.pageIndex + 1)} · ${DateFormat.format("yyyy-MM-dd HH:mm", entry.createdTime)}"
            holder.itemView.setOnClickListener {
                onJumpTo?.invoke(entry)
                dismiss()
            }
            holder.itemView.setOnLongClickListener {
                androidx.appcompat.app.AlertDialog.Builder(ctx)
                    .setTitle(ceui.lisa.R.string.bookmarks_delete_confirm)
                    .setPositiveButton(ceui.lisa.R.string.action_delete) { _, _ -> onDelete?.invoke(entry) }
                    .setNegativeButton(ceui.lisa.R.string.action_cancel, null)
                    .show()
                true
            }
        }

        class VH(itemView: View, val preview: TextView, val footer: TextView) : RecyclerView.ViewHolder(itemView)
    }

    companion object {
        const val TAG = "BookmarksSheet"
    }
}
