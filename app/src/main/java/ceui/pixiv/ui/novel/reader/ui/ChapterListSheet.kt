package ceui.pixiv.ui.novel.reader.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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
import ceui.pixiv.ui.novel.reader.paginate.ChapterOutlineEntry
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Chapter outline as a bottom sheet. Tap to jump and dismiss; sheet opens at
 * the current chapter (centered if possible). Replaces the legacy left-edge
 * drawer.
 */
class ChapterListSheet : BottomSheetDialogFragment() {

    private var chapters: List<ChapterOutlineEntry> = emptyList()
    private var currentSourceStart: Int = 0
    private var onSelected: ((ChapterOutlineEntry) -> Unit)? = null

    private var listView: RecyclerView? = null
    private var currentIndex: Int = -1

    fun configure(
        chapters: List<ChapterOutlineEntry>,
        currentSourceStart: Int,
        onSelected: (ChapterOutlineEntry) -> Unit,
    ) {
        this.chapters = chapters
        this.currentSourceStart = currentSourceStart
        this.onSelected = onSelected
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
            text = "目录"
            setTextColor(Color.BLACK)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
        }
        val count = TextView(ctx).apply {
            text = "${chapters.size} 章"
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

        currentIndex = resolveCurrentIndex()
        val list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter = ChapterAdapter(chapters, currentIndex) { entry ->
                onSelected?.invoke(entry)
                dismissAllowingStateLoss()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
            ).apply { weight = 1f }
        }
        root.addView(list)
        listView = list
        return root
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val displayHeight = resources.displayMetrics.heightPixels
        val targetHeight = (displayHeight * 0.7f).toInt()
        sheet.layoutParams = sheet.layoutParams.apply { height = targetHeight }
        BottomSheetBehavior.from(sheet).apply {
            peekHeight = targetHeight
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
        // Anchor the current chapter near the top so the user immediately sees
        // where they are without hunting through the list.
        listView?.post {
            val lm = listView?.layoutManager as? LinearLayoutManager ?: return@post
            if (currentIndex >= 0) lm.scrollToPositionWithOffset(currentIndex, 0)
        }
    }

    private fun resolveCurrentIndex(): Int {
        if (chapters.isEmpty()) return -1
        for (i in chapters.indices) {
            val here = chapters[i]
            val next = chapters.getOrNull(i + 1)
            if (currentSourceStart >= here.sourceStart && (next == null || currentSourceStart < next.sourceStart)) {
                return i
            }
        }
        return 0
    }

    private class ChapterAdapter(
        private val entries: List<ChapterOutlineEntry>,
        private val currentIndex: Int,
        private val onClick: (ChapterOutlineEntry) -> Unit,
    ) : RecyclerView.Adapter<ChapterAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val density = ctx.resources.displayMetrics.density
            val view = TextView(ctx).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (52 * density).toInt(),
                )
                setPadding((20 * density).toInt(), 0, (16 * density).toInt(), 0)
                gravity = Gravity.CENTER_VERTICAL
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                applySelectableBackground()
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            return VH(view)
        }

        override fun getItemCount(): Int = entries.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = entries[position]
            val tv = holder.itemView as TextView
            tv.text = entry.title
            val isCurrent = position == currentIndex
            tv.setTextColor(if (isCurrent) 0xFF5B6EFF.toInt() else 0xFF222222.toInt())
            tv.setTypeface(tv.typeface, if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
            tv.setOnClickListener { onClick(entry) }
        }

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView)
    }

    companion object {
        const val TAG = "ChapterListSheet"
    }
}
